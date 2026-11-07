package com.beevpn.win.warp

import com.beevpn.win.AppPaths
import com.beevpn.win.config.BeeConfig
import com.beevpn.win.engine.AetherEngine
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import org.json.JSONObject
import java.io.File
import java.io.StringWriter
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/// The phone's identity ladder, desktop edition — the line-by-line port of
/// `IdentityRelayClient.kt` (+ the storage half of `IdentityProvisioner.kt`).
///
/// ## Why this exists
///
/// WireGuard and MASQUE cannot handshake until a device is registered against
/// the Cloudflare account API. On an Iranian carrier that API is poisoned and
/// the TLS handshake is dropped, so a fresh install has NO usable identity and
/// every WARP-family transport dies with «هویت MASQUE/WARP ساخته نشد» — the
/// exact 1.4.0 report: the phone rescued itself through this ladder while the
/// desktop had only the engine's own registration, which dials the API
/// directly and dies on the same carrier.
///
/// ## The ladder (the phone's order, top to bottom)
///
///   1. relay Workers (when the build carries the HMAC secret) — the abuse-
///      barrier-authenticated enrollment the project operates itself;
///   2. the direct API across its host ladder — api.devices.cloudflare.com
///      first (usually unfiltered), then api.cloudflareclient.com;
///   3. (caller continues with the SHARD-backed paths — TunnelController).
///
/// The enrollment itself is the complete flow, on ANY transport:
/// a device-generated X25519 keypair whose public half rides the request
/// ("key" — a registration without it is refused), POST /reg → id + token,
/// PATCH warp_enabled=true (a fresh account can handshake but carries no
/// traffic until this is set), and for MASQUE identities a locally generated
/// secp256r1 certificate pair PATCHed onto the account.
///
/// The pinned-edge API routes Android walks (104.24.4.6 / 104.18.0.6 with a
/// rewritten resolver) are not portable to the JVM's public HTTP stack — they
/// need custom DNS inside TLS, which neither HttpURLConnection nor
/// java.net.http exposes. The two hostname routes plus the relay Workers plus
/// the SHARD-backed paths cover the same ground.
object IdentityRelayClient {

    // Build-time configurable, exactly like Android's BuildConfig fields
    // (RELAY-WORKERS-SETUP-FA.txt). Defaults are the placeholder Workers that
    // cannot answer without a deployed backend behind them.
    val RELAY_A: String = BeeConfig.BEE_RELAY_A
    val RELAY_B: String = BeeConfig.BEE_RELAY_B

    /// A relay without its HMAC secret can never answer — skip the whole ladder.
    val relayConfigured: Boolean =
        BeeConfig.IDENTITY_RELAY_HMAC_B64.isNotBlank() &&
            RELAY_A.startsWith("https://") &&
            !RELAY_A.contains("beevpn-edge-one")

    const val REGISTER_PATH = "/v0a4471/reg"
    const val ENROLL_PATH_PREFIX = "/v0a4471/reg/"
    const val AUTH_TIMESTAMP = "X-BEE-Timestamp"
    const val AUTH_NONCE = "X-BEE-Nonce"
    const val AUTH_SIGNATURE = "X-BEE-Signature"
    const val AUTH_ALGORITHM = "HmacSHA256"
    const val CONNECT_TIMEOUT_MS = 8_000
    const val READ_TIMEOUT_MS = 16_000
    const val DIRECT_API_TIMEOUT_MS = 20_000

    private val API_PRIMARY = "api.devices.cloudflare.com"
    private val API_LEGACY = "api.cloudflareclient.com"

    /// The hosts the SHARD-backed enrollment walks INSIDE the tunnel: the node
    /// resolves remotely, but a locally poisoned name must not stop the attempt.
    private val SOCKS_API_HOSTS = listOf(API_PRIMARY, API_LEGACY)

    val ENDPOINT_LADDER = listOf(RELAY_A, RELAY_B)

    class RelayError(message: String) : Exception(message)

    data class Outcome(val success: Boolean, val error: String)

    /// One completed enrollment: the raw account response plus the material
    /// the caller generated for it — exactly Android's Hop.
    data class Hop(
        val response: JSONObject,
        val wgPrivate: ByteArray,
        val cert: String,
        val key: String,
        val issued: Long,
    )

    /// A transport-agnostic registration call: POST/PATCH with an optional
    /// bearer token — the seam that lets one enrollment drive every transport.
    internal fun interface ApiCall {
        fun call(method: String, path: String, body: String, token: String?): JSONObject
    }

    // ------------------------------------------------------------ enrollment

    /// The complete enrollment flow, on any transport — Android's enrollVia.
    internal fun enrollVia(call: ApiCall, masque: Boolean): Hop {
        val (privateRaw, publicRaw) = newX25519KeyPair()
        val body = JSONObject()
            .put("key", Base64.getEncoder().encodeToString(publicRaw))
            .put("install_id", "")
            .put("fcm_token", "")
            .put(
                "tos",
                java.time.Instant.now().toString()
                    .replace(Regex("\\.\\d+Z$"), "Z"),
            )
            .put("model", "Windows")
            .put("serial_number", "")
            .put("os_version", "")
            .put("key_type", "curve25519")
            .put("tunnel_type", "wireguard")
            .put("locale", java.util.Locale.getDefault().toString())
            .toString()
        val account = call.call("POST", REGISTER_PATH, body, null)
        val deviceId = account.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
            ?: throw RelayError("invalid device id")
        val token = account.optString("token").takeIf { it.isNotEmpty() }
            ?: throw RelayError("missing token")
        // A newly registered WARP account can handshake without carrying traffic
        // until warp_enabled is set. MASQUE needs its own authenticated key PATCH.
        if (!account.optBoolean("warp_enabled", false)) {
            val enabled = call.call(
                "PATCH", ENROLL_PATH_PREFIX + deviceId,
                JSONObject().put("warp_enabled", true).toString(), token,
            )
            if (!enabled.optBoolean("warp_enabled", false)) throw RelayError("WARP was not enabled")
        }
        if (!masque) return Hop(account, privateRaw, "", "", 0)
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
        val issued = System.currentTimeMillis() / 1000
        val name = X500Name("CN=$deviceId")
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger(128, SecureRandom()),
            Date((issued - 60) * 1000), Date((issued + 365L * 86400) * 1000), name, pair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        val update = JSONObject()
            .put("key", Base64.getEncoder().encodeToString(pair.public.encoded))
            .put("key_type", KEY_TYPE_MASQUE)
            .put("tunnel_type", TUNNEL_TYPE_MASQUE)
            .toString()
        call.call("PATCH", ENROLL_PATH_PREFIX + deviceId, update, token)
        return Hop(account, privateRaw, pem(certificate), privatePem(pair.private.encoded), issued)
    }

    /// The full enrollment over a SOCKS5 listener (the SHARD-backed path).
    /// Host ladder inside: api.devices.cloudflare.com first, then
    /// api.cloudflareclient.com.
    internal fun enrollThroughSocks(socks: Proxy, masque: Boolean): Hop {
        var last: Exception? = null
        for (host in SOCKS_API_HOSTS) {
            try {
                return enrollVia({ method, path, body, token ->
                    httpJson(host, method, path, body, token, proxy = socks)
                }, masque)
            } catch (e: Exception) {
                last = e
            }
        }
        throw RelayError(last?.message ?: "registration through SHARD failed")
    }

    // -------------------------------------------------------------- ladders

    /// The phone's `provision`: for every identity file the protocol needs,
    /// walk relay Workers → direct API until one yields a complete identity,
    /// then install all of them atomically. Missing files only — a complete
    /// identity on disk is never touched. `allowDirect` mirrors the phone's
    /// accountApiReachable cache: a carrier already measured as blocked pays
    /// no dead direct-API timeouts — the ladder goes straight to the relays
    /// and the caller continues with the SHARD-backed paths.
    fun provision(protocol: AetherEngine.Protocol, allowDirect: Boolean = true): Outcome {
        val names = when (protocol) {
            AetherEngine.Protocol.MIM -> listOf("masque", "masque-secondary")
            AetherEngine.Protocol.MASQUE -> listOf("masque")
            AetherEngine.Protocol.GOOL -> listOf("wireguard", "secondary")
            AetherEngine.Protocol.WG -> listOf("wireguard")
        }
        val prepared = mutableListOf<Pair<File, String>>()
        for (name in names) {
            val file = identityFile(name)
            if (AetherEngine.identityComplete(file)) continue
            var failure = "relay unavailable"
            var text: String? = null
            if (!relayConfigured) {
                // No HMAC secret / placeholder Workers: the ladder cannot
                // succeed, and walking it costs up to ~90 s of dead timeouts
                // per identity before the direct API is even tried.
                log("identity relay: disabled (no HMAC secret / placeholder Workers) — using direct API")
            }
            for (endpoint in ENDPOINT_LADDER.takeIf { relayConfigured } ?: emptyList()) {
                try {
                    val hop = enrollVia({ method, path, body, token ->
                        relayRequest(endpoint, method, path, body, token)
                    }, name.startsWith("masque"))
                    text = relayToml(hop)
                    if (text == null) failure = "invalid relay account response"
                } catch (e: Exception) {
                    failure = e.message?.take(100) ?: e.javaClass.simpleName
                }
                if (text != null) break
            }
            if (text == null && allowDirect) {
                try {
                    val hop = enrollVia({ method, path, body, token ->
                        httpJson(API_PRIMARY, method, path, body, token)
                    }, name.startsWith("masque"))
                    text = relayToml(hop)
                    if (text == null) failure = "invalid direct API response"
                } catch (e: Exception) {
                    failure = "relay=$failure; api=${e.message?.take(100) ?: e.javaClass.simpleName}"
                }
            }
            if (text == null) return Outcome(false, failure)
            prepared += file to text
        }
        if (prepared.isEmpty()) {
            // Every identity this protocol needs was already on disk.
            return Outcome(true, "identity already present")
        }
        return if (installRelayedFiles(prepared)) Outcome(true, "identity stored")
        else Outcome(false, "could not store identity")
    }

    private fun identityFile(name: String): File = when (name) {
        "masque" -> AetherEngine.masquePath()
        "masque-secondary" -> AetherEngine.masqueSecondaryPath()
        "secondary" -> AetherEngine.secondaryPath()
        else -> AetherEngine.configPath()
    }

    /// The SHARD-backed enrollment over a live SOCKS5 listener — the desktop
    /// IdentityProvisioner.provisionThroughShard: speaks the registration API
    /// itself through the tunnel (full enrollment, host ladder inside) and
    /// stores every identity file the protocol needs. Runs when the engine's
    /// own register cannot, so an old/broken binary still leaves a complete
    /// identity on disk.
    fun provisionThroughSocks(protocol: AetherEngine.Protocol, socks: Proxy): Outcome {
        val names = when (protocol) {
            AetherEngine.Protocol.MIM -> listOf("masque", "masque-secondary")
            AetherEngine.Protocol.MASQUE -> listOf("masque")
            AetherEngine.Protocol.GOOL -> listOf("wireguard", "secondary")
            AetherEngine.Protocol.WG -> listOf("wireguard")
        }
        val prepared = mutableListOf<Pair<File, String>>()
        for (name in names) {
            val file = identityFile(name)
            if (AetherEngine.identityComplete(file)) continue
            val hop = enrollThroughSocks(socks, name.startsWith("masque"))
            val text = relayToml(hop)
                ?: throw RelayError("invalid registration response for $name")
            prepared += file to text
        }
        if (prepared.isEmpty()) return Outcome(true, "identity already present")
        return if (installRelayedFiles(prepared)) Outcome(true, "identity stored")
        else Outcome(false, "could not store identity")
    }

    // ------------------------------------------------------------ transport

    /// One HTTPS JSON request, plain or through a SOCKS5 proxy — the desktop
    /// rendering of Android's `socksRequest` (and of OkHttp's direct call).
    /// The headers are the exact set the API is picky about.
    private fun httpJson(
        host: String,
        method: String,
        path: String,
        body: String,
        token: String?,
        proxy: Proxy? = null,
    ): JSONObject {
        repeat(2) { attempt ->
            val connection = (URI("https", null, host, 443, path, null, null).toURL()
                .openConnection(proxy ?: Proxy.NO_PROXY) as HttpURLConnection)
            try {
                connection.connectTimeout = if (proxy != null) DIRECT_API_TIMEOUT_MS else CONNECT_TIMEOUT_MS
                connection.readTimeout = DIRECT_API_TIMEOUT_MS
                connection.requestMethod = method
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "WARP for Android")
                connection.setRequestProperty("CF-Client-Version", "a-6.35-4471")
                if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code in 200..299) {
                    return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                }
                if ((code == 429 || code >= 500) && attempt == 0) {
                    val retrySeconds = connection.getHeaderField("Retry-After")?.toLongOrNull()
                        ?.coerceAtMost(30) ?: 1L
                    Thread.sleep(retrySeconds * 1000L)
                    return@repeat
                }
                throw RelayError("registration HTTP $code")
            } finally {
                connection.disconnect()
            }
        }
        throw RelayError("registration retry exhausted")
    }

    /// One relay-Worker request with the injected HMAC — an abuse barrier, not
    /// anti-clone (Android's `request`, byte for byte).
    private fun relayRequest(endpoint: String, method: String, path: String, body: String, token: String?): JSONObject {
        repeat(2) { attempt ->
            val timestamp = System.currentTimeMillis().toString()
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            val signatureInput = "$method\n$path\n$timestamp\n$nonce\n$body"
            val mac = Mac.getInstance(AUTH_ALGORITHM).apply {
                init(
                    SecretKeySpec(
                        Base64.getMimeDecoder().decode(BeeConfig.IDENTITY_RELAY_HMAC_B64),
                        AUTH_ALGORITHM,
                    ),
                )
            }
            val signature = mac.doFinal(signatureInput.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val connection = (URI(endpoint + path).toURL().openConnection() as HttpURLConnection)
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.requestMethod = method
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "WARP for Android")
                connection.setRequestProperty("CF-Client-Version", "a-6.35-4471")
                connection.setRequestProperty(AUTH_TIMESTAMP, timestamp)
                connection.setRequestProperty(AUTH_NONCE, nonce)
                connection.setRequestProperty(AUTH_SIGNATURE, signature)
                if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code in 200..299) {
                    return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                }
                if ((code == 429 || code >= 500) && attempt == 0) {
                    val retrySeconds = connection.getHeaderField("Retry-After")?.toLongOrNull()
                        ?.coerceAtMost(30) ?: 1L
                    Thread.sleep(retrySeconds * 1000L)
                    return@repeat
                }
                throw RelayError("relay HTTP $code")
            } finally {
                connection.disconnect()
            }
        }
        throw RelayError("relay retry exhausted")
    }

    // -------------------------------------------------------------- storage

    /// The registration response → the aether TOML the core reads — the same
    /// field set and the same escaping IdentityProvisioner.relayToml persists
    /// on the phone. Null on a response that lacks anything the tunnel needs.
    internal fun relayToml(hop: Hop): String? {
        val response = hop.response
        val config = response.optJSONObject("config") ?: return null
        val addresses = config.optJSONObject("interface")?.optJSONObject("addresses") ?: return null
        val peers = config.optJSONArray("peers") ?: return null
        val peer = peers.optJSONObject(0) ?: return null
        fun b64(value: String, length: Int): String? = runCatching {
            val bytes = Base64.getDecoder().decode(value)
            if (bytes.size == length) value else null
        }.getOrNull()
        val peerKey = b64(peer.optString("public_key"), 32) ?: return null
        val clientId = b64(config.optString("client_id"), 3) ?: ""
        if (hop.wgPrivate.size != 32 || response.optString("id").isBlank() ||
            response.optString("token").isBlank() || addresses.optString("v4").isBlank()
        ) return null
        if ((hop.cert.isBlank() || hop.key.isBlank()) && hop.issued != 0L) return null
        if (hop.issued != 0L &&
            (!hop.cert.contains("BEGIN CERTIFICATE") || !hop.key.contains("BEGIN PRIVATE KEY"))
        ) return null
        val account = response.optJSONObject("account")
        val services = config.optJSONObject("services")
        val endpoint = peer.optJSONObject("endpoint")?.optString("v4").orEmpty().substringBefore(':')
        return buildString {
            appendLine("device_id = " + q(response.optString("id")))
            appendLine("access_token = " + q(response.optString("token")))
            appendLine("cert_pem = " + q(hop.cert))
            appendLine("key_pem = " + q(hop.key))
            appendLine("cert_issued_at = ${hop.issued}")
            appendLine("ipv4 = " + q(addresses.optString("v4")))
            appendLine("ipv6 = " + q(addresses.optString("v6")))
            appendLine("wg_private_key = " + q(Base64.getEncoder().encodeToString(hop.wgPrivate)))
            appendLine("wg_peer_public_key = " + q(peerKey))
            appendLine("client_id = " + q(clientId))
            appendLine("organization = " + q(account?.optString("organization").orEmpty()))
            appendLine("gateway_proxy = " + q(services?.optString("http_proxy").orEmpty()))
            appendLine("assigned_endpoint = " + q(endpoint))
        }.trimEnd('\n') + "\n"
    }

    /// Atomic multi-file install with rollback — IdentityProvisioner's
    /// installRelayedFiles: stage → validate → backup → swap → clean, and a
    /// failure anywhere puts the old identities back byte for byte.
    internal fun installRelayedFiles(files: List<Pair<File, String>>): Boolean {
        val staged = mutableListOf<Pair<File, File>>()
        val backups = mutableListOf<Pair<File, File>>()
        val installed = mutableListOf<File>()
        try {
            for ((target, text) in files) {
                val temp = File(target.parentFile, target.name + ".relay-new")
                temp.writeText(text)
                if (!AetherEngine.identityComplete(temp)) throw RelayError("invalid staged identity")
                staged += target to temp
            }
            for ((target, temp) in staged) {
                val backup = File(target.parentFile, target.name + ".relay-old")
                if (target.exists()) {
                    if (backup.exists()) throw RelayError("previous relay recovery pending")
                    if (!target.renameTo(backup)) throw RelayError("identity backup failed")
                    backups += target to backup
                }
                if (!temp.renameTo(target)) throw RelayError("identity install failed")
                installed += target
            }
            backups.forEach { (_, backup) -> backup.delete() }
            return true
        } catch (_: Exception) {
            installed.forEach { it.delete() }
            backups.forEach { (target, backup) -> backup.renameTo(target) }
            return false
        } finally {
            staged.forEach { (_, temp) -> temp.delete() }
        }
    }

    /// TOML string quoting — the escaping relayToml() applies.
    internal fun q(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\""

    /// X25519 (privateRaw, publicRaw) — both the trailing 32 raw bytes of
    /// their JDK encodings, the exact bytes the API's "key" field wants.
    private fun newX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val kpg = KeyPairGenerator.getInstance("X25519")
        // X25519 is a NAMED curve: initialize(int) is a key SIZE and 256 is
        // unsupported — every registration died here with
        // InvalidParameterException before a single byte hit the wire.
        kpg.initialize(java.security.spec.NamedParameterSpec.X25519, SecureRandom())
        val kp = kpg.generateKeyPair()
        val priv = kp.private.encoded
        val pub = kp.public.encoded
        return Pair(
            priv.copyOfRange(priv.size - 32, priv.size),
            pub.copyOfRange(pub.size - 32, pub.size),
        )
    }

    private const val KEY_TYPE_MASQUE = "secp256r1"
    private const val TUNNEL_TYPE_MASQUE = "masque"

    private fun privatePem(encoded: ByteArray): String =
        StringWriter().also { writer ->
            PemWriter(writer).use { it.writeObject(PemObject("PRIVATE KEY", encoded)) }
        }.toString()

    private fun pem(value: java.security.cert.Certificate): String =
        StringWriter().also { writer ->
            PemWriter(writer).use { it.writeObject(PemObject("CERTIFICATE", value.encoded)) }
        }.toString()

    internal fun log(line: String) {
        try {
            File(AppPaths.logsDir, "identity.log").appendText("[relay] $line\n")
        } catch (_: Exception) {}
    }
}
