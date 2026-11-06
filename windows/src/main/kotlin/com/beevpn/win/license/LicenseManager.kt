package com.beevpn.win.license

import com.beevpn.win.SecureStore
import com.beevpn.win.config.BeeConfig
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/// The Windows mirror of LicenseManager.kt — one source of truth for the
/// entitlement on this device.
///
///   * activate / login talk to the Worker and store the Ed25519 token in the
///     SecureStore (%APPDATA%/BEE-VPN/store.json),
///   * refreshState() re-verifies the token OFFLINE on every call (signature +
///     device binding + clock) — the app keeps working until the token's own
///     `e`, exactly like Android,
///   * verifyInBackground() runs /verify every ~6 h so revocation and admin
///     extends propagate (Android uses ~12 h; a tighter cadence costs one
///     tiny request and this machine has no battery to save).
object LicenseManager {

    enum class Gate { UNKNOWN, GRANTED, LOCKED }

    // SecureStore keys — the same shape the iOS Keychain uses.
    private const val K_TOKEN = "license.token"
    private const val K_SUBJECT = "license.subject"
    private const val K_KIND = "license.kind"
    private const val K_ACTIVATED_AT = "license.activatedAt"
    private const val K_LAST_CHECK = "license.lastCheck"
    private const val K_INSTALL_ID = "license.installId"

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Snapshot state — the UI observes the gate reactively.
    var gate: Gate by androidx.compose.runtime.mutableStateOf(Gate.UNKNOWN)
        private set
    var lockReason: String? by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    @Volatile var lastVerifyAt: Long = 0

    private val started = AtomicBoolean(false)

    val isConfigured: Boolean
        get() = BeeConfig.LICENSE_URL.isNotEmpty() && BeeConfig.LICENSE_PUBKEY_B64.isNotEmpty()

    /// 32 hex chars — the same shape Android generates (UUID without dashes)
    /// and the same shape the Worker's normalizeInstallId accepts.
    val installId: String by lazy {
        val saved = SecureStore.get(K_INSTALL_ID)
        if (saved != null && saved.length == 32) saved
        else UUID.randomUUID().toString().replace("-", "").lowercase().also {
            SecureStore.put(K_INSTALL_ID, it)
        }
    }

    val storedToken: String?
        get() = SecureStore.get(K_TOKEN)?.takeIf { it.isNotEmpty() }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            refreshState()
        } catch (t: Throwable) {
            // A broken store must never crash the launch — show the gate instead.
            gate = Gate.LOCKED
            lockReason = null
        }
        scheduleBackgroundVerification()
    }

    // ------------------------------------------------------------- state

    fun refreshState() {
        if (!isConfigured) { gate = Gate.GRANTED; return }  // build without a server: pass-through
        val token = storedToken
        if (token == null) { gate = Gate.LOCKED; lockReason = null; return }
        if (BeeCrypto.verifyToken(token, BeeConfig.LICENSE_PUBKEY_B64, installId) != null) {
            gate = Gate.GRANTED
        } else {
            gate = Gate.LOCKED; lockReason = null
        }
    }

    // ------------------------------------------------------------ actions

    /// Activates a BEE-XXXX-XXXX-XXXX code. Returns null on success, a
    /// buyer-readable Persian error otherwise. Catches Throwable — an Error
    /// must never escape into a UI worker thread and freeze the button.
    fun activate(code: String): String? {
        val clean = code.uppercase().trim()
        return try {
            val resp = LicenseAPI.activate(clean, installId)
            store(resp)
        } catch (e: LicenseAPI.LicenseError) {
            e.message
        } catch (t: Throwable) {
            "خطای غیرمنتظره: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    fun login(username: String, password: String): String? = try {
        val resp = LicenseAPI.login(username.trim(), password, installId)
        store(resp)
    } catch (e: LicenseAPI.LicenseError) {
        e.message
    } catch (t: Throwable) {
        "خطای غیرمنتظره: ${t.message ?: t.javaClass.simpleName}"
    }

    /// Persists a successful /activate or /login response and flips state.
    private fun store(resp: JSONObject): String? {
        val token = resp.optString("token", "")
        val subject = resp.optString("subject", "")
        val plan = resp.optString("plan", "")
        val kind = resp.optString("kind", "")
        if (token.isEmpty() || subject.isEmpty() || plan.isEmpty() || kind.isEmpty()) {
            return "پاسخ سرور ناقص بود — دوباره تلاش کن"
        }
        // Trust only after our own Ed25519 verification, like the Android app.
        if (BeeCrypto.verifyToken(token, BeeConfig.LICENSE_PUBKEY_B64, installId) == null) {
            return "امضای توکن سرور معتبر نبود — با پشتیبانی تماس بگیر"
        }
        val now = System.currentTimeMillis() / 1000L
        val hadToken = storedToken != null
        SecureStore.put(K_TOKEN, token)
        SecureStore.put(K_SUBJECT, subject)
        SecureStore.put(K_KIND, kind)
        if (!hadToken) SecureStore.put(K_ACTIVATED_AT, now.toString())
        SecureStore.put(K_LAST_CHECK, now.toString())
        lastVerifyAt = now
        gate = Gate.GRANTED
        return null
    }

    fun signOut() {
        SecureStore.remove(K_TOKEN)
        SecureStore.remove(K_SUBJECT)
        SecureStore.remove(K_KIND)
        SecureStore.remove(K_ACTIVATED_AT)
        SecureStore.remove(K_LAST_CHECK)
        gate = Gate.LOCKED
        lockReason = null
    }

    /// /verify in the background: revocation + admin extends land here.
    /// Never blocks the UI; on hard failure the offline token keeps deciding.
    fun verifyInBackground() {
        if (!isConfigured) return
        val token = storedToken ?: return
        scope.launch {
            try {
                val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    LicenseAPI.verify(token, installId)
                }
                val fresh = resp.optString("token", "")
                if (fresh.isNotEmpty() &&
                    BeeCrypto.verifyToken(fresh, BeeConfig.LICENSE_PUBKEY_B64, installId) != null
                ) {
                    val now = System.currentTimeMillis() / 1000L
                    SecureStore.put(K_TOKEN, fresh)
                    SecureStore.put(K_LAST_CHECK, now.toString())
                    lastVerifyAt = now
                    gate = Gate.GRANTED
                }
            } catch (e: LicenseAPI.LicenseError) {
                if (e is LicenseAPI.LicenseError.Server) {
                    // The server says no — the offline clock must not override it.
                    if (e.message!!.startsWith("این اشتراک باطل شده") ||
                        e.message!!.startsWith("اشتراک به پایان رسیده")
                    ) {
                        lockReason = e.message
                        gate = Gate.LOCKED
                    }
                }
            } catch (_: Exception) {
                // network trouble: keep the offline verdict
            }
        }
    }

    private fun scheduleBackgroundVerification() {
        val timer = java.util.Timer(true)
        timer.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() = verifyInBackground()
        }, 6 * 3600 * 1000L, 6 * 3600 * 1000L)
    }

    // ------------------------------------------------- subscription info

    /// The same numbers the VPN gate enforces — the profile screen reads this,
    /// so the profile can never disagree with the gate (like Android's
    /// SubscriptionInfoActivity and the iOS SubscriptionView).
    data class SubscriptionInfo(
        val configured: Boolean,
        val hasToken: Boolean,
        val plan: String,
        val kind: String,
        val subject: String,
        val installId: String,
        val expiryEpochSec: Long,
        val activatedAtEpochSec: Long,
        val lastCheckEpochSec: Long,
    )

    fun subscriptionInfo(): SubscriptionInfo {
        val now = System.currentTimeMillis() / 1000L
        val token = storedToken
            ?: return SubscriptionInfo(isConfigured, false, "", "", "", installId, 0, 0, 0)
        val e = BeeCrypto.verifyToken(token, BeeConfig.LICENSE_PUBKEY_B64, installId)
            ?: return SubscriptionInfo(isConfigured, false, "", "", "", installId, 0, 0, 0)
        val activatedAt = SecureStore.get(K_ACTIVATED_AT)?.toLongOrNull() ?: 0L
        val lastCheck = SecureStore.get(K_LAST_CHECK)?.toLongOrNull() ?: 0L
        return SubscriptionInfo(
            configured = isConfigured, hasToken = true,
            plan = e.plan, kind = e.kind, subject = e.subject, installId = installId,
            expiryEpochSec = e.expiry,
            activatedAtEpochSec = activatedAt,
            lastCheckEpochSec = if (lastCheck > 0) lastCheck else now,
        )
    }
}
