package com.msnguard.vpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Subscription gate for BEE-VPN.
 *
 * ## The shape of the system
 *
 * The app never talks to a payment provider and never decides who paid. It
 * holds one entitlement token, issued by the license server (license-worker.js,
 * a Cloudflare Worker — see license/LICENSE-SETUP-FA.txt) after either an
 * activation code or a username/password check, and bound to exactly one
 * device. The token is `v1.<payloadB64url>.<sigB64url>` with an Ed25519
 * signature over the payload JSON {s,k,p,i,e,t}. Verification here is purely
 * local: signature (public key compiled in), device match (`i` == install id)
 * and clock (`e` > now).
 *
 * That local-verifiable shape has a deliberate consequence: there is no
 * separate "grace period" — the remaining subscription IS the token's own
 * `e`. A user with no network for a week keeps working until the expiry they
 * already own; revocation propagates the next time [revalidateAsync] gets
 * through (~12 h cadence, plus on every resume).
 *
 * ## Why the security is all server-side
 *
 * This source is AGPL and public; anyone can read this file and strip the
 * gate out of their own build. That is fine: a stripped build still has no
 * valid token and the license server will never issue one for a device the
 * operator has not sold to. The only secret that matters (the Ed25519 private
 * key) never leaves the Worker. The public key here is public by design.
 *
 * ## Opt-in by build
 *
 * [configured] is false unless the CI injected BEE_LICENSE_URL and
 * BEE_LICENSE_PUBKEY_B64. Without them every check below passes and the app
 * behaves exactly like the pre-license builds — the operator can ship the
 * service first and switch the paywall on by setting two GitHub secrets.
 */
object LicenseManager {

    private const val PREFS = "bee_license"
    private const val K_INSTALL_ID = "install_id"
    private const val K_KIND = "kind"          // code | account
    private const val K_SUBJECT = "subject"    // code or username, for display
    private const val K_LAST_CHECK = "last_check"
    private const val TOKEN_KEY = "license_token"   // stored via SecureStore (AES/GCM)

    private const val VERIFY_INTERVAL_SEC = 12 * 60 * 60L

    private val revalidating = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** One entitlement question, answered once: may this device connect? */
    data class Entitlement(
        val entitled: Boolean,
        val subject: String,
        val kind: String,
        val expiryEpochSec: Long,
        val reason: String,   // "unconfigured" | "none" | "invalid" | "device" | "expired" | ""
    )

    // ------------------------------------------------------------------ state

    /** Whether this build carries a license server at all. */
    fun configured(): Boolean =
        BuildConfig.BEE_LICENSE_URL.startsWith("https://") &&
            BuildConfig.BEE_LICENSE_PUBKEY_B64.isNotBlank()

    /**
     * Stable per-install identifier sent to the server for device binding.
     * Own prefs rather than IdentityRelayClient's: clearing data or a
     * reinstall legitimately changes it, and the operator's /reset is the
     * documented remedy for a changed phone.
     */
    fun instanceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(K_INSTALL_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(K_INSTALL_ID, fresh).apply()
        return fresh
    }

    /** Full local check: token present, signature valid, device matches, not expired. */
    fun entitlement(context: Context): Entitlement {
        if (!configured()) {
            return Entitlement(true, "", "", 0, "unconfigured")
        }
        val token = SecureStore.getSecret(context, TOKEN_KEY)
        if (token.isBlank()) return Entitlement(false, "", "", 0, "none")
        val payload = verifyToken(token) ?: return Entitlement(false, "", "", 0, "invalid")
        if (payload.optString("i") != instanceId(context)) {
            return Entitlement(false, "", "", 0, "device")
        }
        val expiry = payload.optLong("e", 0L)
        if (expiry <= System.currentTimeMillis() / 1000) {
            return Entitlement(false, payload.optString("s"), payload.optString("k"), expiry, "expired")
        }
        return Entitlement(
            true,
            payload.optString("s"),
            payload.optString("k", "code"),
            expiry,
            "",
        )
    }

    fun isEntitled(context: Context): Boolean = entitlement(context).entitled

    /** The single question the VPN service asks before raising a tunnel. */
    fun blocked(context: Context): Boolean = configured() && !isEntitled(context)

    /** Whole days left on the entitlement, minimum 1 while it is valid. */
    fun daysRemaining(context: Context): Int {
        val e = entitlement(context)
        if (!e.entitled || e.expiryEpochSec <= 0) return 0
        val seconds = e.expiryEpochSec - System.currentTimeMillis() / 1000
        return (seconds / 86400).toInt() + 1
    }

    fun signOut(context: Context) {
        SecureStore.removeSecret(context, TOKEN_KEY)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(K_SUBJECT).remove(K_KIND).remove(K_LAST_CHECK).apply()
    }

    // -------------------------------------------------------------- activate

    /**
     * Activation code path. Runs on a worker thread, answers on main.
     * [onResult] carries (ok, localized message).
     */
    fun activateWithCode(context: Context, code: String, onResult: (Boolean, String) -> Unit) {
        val body = JSONObject()
            .put("code", code.trim())
            .put("install_id", instanceId(context))
            .put("model", android.os.Build.MODEL)
        request(context, "/activate", body, onResult)
    }

    /** Username/password path — the account the admin issued via /adduser. */
    fun login(context: Context, username: String, password: String, onResult: (Boolean, String) -> Unit) {
        val body = JSONObject()
            .put("username", username.trim())
            .put("password", password)
            .put("install_id", instanceId(context))
        request(context, "/login", body, onResult)
    }

    private fun request(
        context: Context,
        path: String,
        body: JSONObject,
        onResult: (Boolean, String) -> Unit,
    ) {
        val appContext = context.applicationContext
        Thread {
            var ok = false
            var message: String
            try {
                val request = Request.Builder()
                    .url(BuildConfig.BEE_LICENSE_URL.trimEnd('/') + path)
                    .header("User-Agent", "BEE-VPN")
                    .post(body.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                    .build()
                httpClient().newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    val token = json?.optString("token").orEmpty()
                    if (response.isSuccessful && token.isNotBlank()) {
                        store(appContext, token, json!!.optString("subject"), json.optString("kind", "code"))
                        ok = true
                        message = Strings.t("Subscription activated ✓")
                    } else {
                        message = localizeError(json?.optString("error").orEmpty())
                    }
                }
            } catch (e: Exception) {
                message = Strings.t("License server unreachable — try again")
            }
            val resultOk = ok
            val resultMessage = message
            mainHandler.post { onResult(resultOk, resultMessage) }
        }.start()
    }

    // ------------------------------------------------------------- revalidate

    /**
     * Fire-and-forget server check for revocation and admin-extended expiry.
     * Cheap by construction: at most one request per [VERIFY_INTERVAL_SEC],
     * silent on any failure (the token's own `e` covers offline time).
     */
    fun revalidateAsync(context: Context) {
        if (!configured() || !revalidating.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread {
            try {
                val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val now = System.currentTimeMillis() / 1000
                if (now - prefs.getLong(K_LAST_CHECK, 0L) < VERIFY_INTERVAL_SEC) return@Thread
                val token = SecureStore.getSecret(appContext, TOKEN_KEY)
                if (token.isBlank()) return@Thread
                val body = JSONObject()
                    .put("token", token)
                    .put("install_id", instanceId(appContext))
                val request = Request.Builder()
                    .url(BuildConfig.BEE_LICENSE_URL.trimEnd('/') + "/verify")
                    .header("User-Agent", "BEE-VPN")
                    .post(body.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                    .build()
                httpClient().newCall(request).execute().use { response ->
                    prefs.edit().putLong(K_LAST_CHECK, System.currentTimeMillis() / 1000).apply()
                    if (response.code == 403) {
                        // Revoked, expired or rebound: the local token is dead weight now.
                        signOut(appContext)
                        return@Thread
                    }
                    if (!response.isSuccessful) return@Thread
                    val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull() ?: return@Thread
                    val fresh = json.optString("token").orEmpty()
                    if (fresh.isNotBlank()) {
                        store(appContext, fresh, json.optString("subject"), json.optString("kind", "code"))
                    }
                }
            } catch (_: Exception) {
                // Offline, blocked domain, timeout — the token still stands.
            } finally {
                revalidating.set(false)
            }
        }.start()
    }

    // ------------------------------------------------------------------ internals

    private fun store(context: Context, token: String, subject: String, kind: String) {
        SecureStore.putSecret(context, TOKEN_KEY, token)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_SUBJECT, subject)
            .putString(K_KIND, kind)
            .putLong(K_LAST_CHECK, System.currentTimeMillis() / 1000)
            .apply()
    }

    private fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(16, TimeUnit.SECONDS)
        .build()

    private fun b64url(text: String): ByteArray =
        Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /**
     * Ed25519 over the raw payload bytes. Returns the decoded payload JSON or
     * null — null is deliberately indistinguishable between "bad signature"
     * and "garbled token": both mean "not entitled".
     */
    private fun verifyToken(token: String): JSONObject? {
        return try {
            val parts = token.split(".")
            if (parts.size != 3 || parts[0] != "v1") return null
            val payload = b64url(parts[1])
            val signature = b64url(parts[2])
            val publicKey = Ed25519PublicKeyParameters(
                b64url(BuildConfig.BEE_LICENSE_PUBKEY_B64), 0,
            )
            val verifier = Ed25519Signer()
            verifier.init(false, publicKey)
            verifier.update(payload, 0, payload.size)
            if (!verifier.verifySignature(signature)) return null
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun localizeError(serverError: String): String = when {
        serverError.contains("unknown code") -> Strings.t("Invalid activation code")
        serverError.contains("bound to another device") -> Strings.t("This code is bound to another device")
        serverError.contains("expired") -> Strings.t("Subscription expired — renew to continue")
        serverError.contains("revoked") -> Strings.t("This subscription was revoked")
        serverError.contains("wrong username or password") -> Strings.t("Wrong username or password")
        serverError.contains("too many attempts") -> Strings.t("Too many attempts — try again later")
        else -> Strings.t("License server unreachable — try again")
    }
}
