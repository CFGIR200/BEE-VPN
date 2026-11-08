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
import java.net.Socket
import java.net.URI
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

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
/// The direct-API routes are the phone's EXACT four (IdentityRelayClient
/// apiRoutes): the two hostnames first, then the two pinned Cloudflare edge
/// IPs with the API's name kept for SNI, the HTTP Host and the certificate
/// check — the resolver is bypassed entirely, so a poisoned answer for
/// api.devices.cloudflare.com cannot stop the registration. The 1.5.0 port
/// shipped without them (a comment claimed the JVM cannot do custom DNS
/// inside TLS — it can: a plain socket to the IP, wrapped by
/// SSLSocketFactory.createSocket(socket, host, 443, true) — and the field
/// kept reporting «هویت MASQUE ساخته نشد» on networks the phone connects
/// from, because the phone rides exactly these edges).
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
    private const val PROBE_TIMEOUT_MS = 8_000

    private val API_PRIMARY = "api.devices.cloudflare.com"
    private val API_LEGACY = "api.cloudflareclient.com"

    /// The pinned edges — the phone's API_EDGES, byte for byte.
    private val API_EDGES = listOf("104.24.4.6", "104.18.0.6")

    /// One direct-API route: the address the TCP connection goes to and the
    /// name that stays on SNI, the HTTP Host and the certificate check.
    internal data class ApiRoute(val connectAddress: String, val host: String)

    /// The phone's `apiRoutes()`: both hostnames, then each pinned edge with
    /// the primary API host rewritten on top of it. Order is load-bearing —
    /// the devices host is usually unfiltered, the edges survive a poisoned
    /// resolver, the legacy host comes first on the phone only inside SOCKS.
    internal val API_ROUTES: List<ApiRoute> =
        listOf(ApiRoute(API_PRIMARY, API_PRIMARY), ApiRoute(API_LEGACY, API_LEGACY)) +
            API_EDGES.map { ApiRoute(it, API_PRIMARY) }

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
                    socksApiRequest(socks, host, method, path, body, token)
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
    /// identity on disk is never touched.
    ///
    /// The direct rung is NEVER skipped — the phone never skips it either: its
    /// accountApiReachable cache only steers the SHARD orchestration, and on a
    /// filtered carrier the ladder's pinned-edge routes are precisely what
    /// still works while every hostname route is dead. The 1.6.1 desktop
    /// skipped the whole rung behind a hostname-only probe that always said
    /// "blocked" on those same carriers — the field report
    /// «هویت MASQUE ساخته نشد».
    fun provision(protocol: AetherEngine.Protocol): Outcome {
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
            if (text == null) {
                try {
                    val hop = enrollVia({ method, path, body, token ->
                        directApiRequest(method, path, body, token)
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

    /// The direct-API request — the desktop rendering of Android's
    /// `apiRequest`: ONE attempt per route across the full ladder (both
    /// hostnames, then the two pinned edges), 429/5xx sleeps the phone's
    /// exponential backoff and moves to the next route, a carrier block page
    /// (HTML where JSON belongs) moves on silently, any other non-2xx refuses
    /// outright. Every call of the enrollment (POST /reg, the warp_enabled
    /// PATCH, the MASQUE key PATCH) walks the ladder independently — exactly
    /// what `enrollDirect` does on the phone.
    private fun directApiRequest(method: String, path: String, body: String, token: String?): JSONObject {
        var last = "API unavailable"
        for ((index, route) in API_ROUTES.withIndex()) {
            try {
                val exchange = PinnedHttp.exchange(
                    connectAddress = route.connectAddress, host = route.host,
                    method = method, path = path, body = body, token = token,
                    proxy = null,
                    connectTimeoutMs = DIRECT_API_TIMEOUT_MS, readTimeoutMs = DIRECT_API_TIMEOUT_MS,
                )
                if (exchange.status in 200..299) return JSONObject(exchange.bodyText())
                val retryMs = exchange.header("retry-after")?.toLongOrNull()
                    ?.coerceAtMost(30)?.times(1000) ?: 0L
                last = "api ${exchange.status}"
                if (exchange.status == 429 || exchange.status >= 500) {
                    Thread.sleep(if (retryMs > 0) retryMs else min(15_000L, 900L * (1L shl index.coerceAtMost(4))))
                    continue
                }
                if (exchange.bodyText().trimStart().startsWith("<")) continue
                error(last)
            } catch (e: Exception) {
                // the phone's catch: a refusal is remembered in `last` and the
                // ladder moves to the next route — every route is always tried
                last = e.message?.take(120) ?: e.javaClass.simpleName
            }
        }
        throw RelayError(last)
    }

    /// The direct-API probe across the FULL route ladder — the honest verdict
    /// the blocked cache deserves. The 1.6.1 probe dialled only the two
    /// hostnames, so on a carrier that poisons those names it always recorded
    /// "blocked" while the pinned edges — the routes that actually work — sat
    /// untried. Any HTTP status on any route proves the route open; only a
    /// total transport failure everywhere counts as blocked. The body is the
    /// phone's deliberately-invalid `{}`: a 400 still proves the route, and a
    /// valid body would burn a device slot on every probe.
    internal fun probeDirectApi(): Boolean {
        for (route in API_ROUTES) {
            try {
                val exchange = PinnedHttp.exchange(
                    connectAddress = route.connectAddress, host = route.host,
                    method = "POST", path = REGISTER_PATH, body = "{}", token = null,
                    proxy = null,
                    connectTimeoutMs = PROBE_TIMEOUT_MS, readTimeoutMs = PROBE_TIMEOUT_MS,
                )
                if (exchange.status in 200..599) return true
            } catch (_: Exception) {
                // next route
            }
        }
        return false
    }

    /// One HTTPS JSON request through a SOCKS5 listener — the desktop
    /// rendering of Android's `socksRequest`. The hostname is handed to the
    /// socket UNRESOLVED, so the node (xray) does the DNS lookup remotely and
    /// a locally poisoned answer for the API's name cannot stop the attempt —
    /// the exact intent the 1.5.0 comment stated, now actually true.
    private fun socksApiRequest(socks: Proxy, host: String, method: String, path: String, body: String, token: String?): JSONObject {
        repeat(2) { attempt ->
            val exchange = PinnedHttp.exchange(
                connectAddress = host, host = host,
                method = method, path = path, body = body, token = token,
                proxy = socks,
                connectTimeoutMs = DIRECT_API_TIMEOUT_MS, readTimeoutMs = DIRECT_API_TIMEOUT_MS,
            )
            if (exchange.status in 200..299) return JSONObject(exchange.bodyText())
            if ((exchange.status == 429 || exchange.status >= 500) && attempt == 0) {
                val retrySeconds = exchange.header("retry-after")?.toLongOrNull()
                    ?.coerceAtMost(30) ?: 1L
                Thread.sleep(retrySeconds * 1000L)
                return@repeat
            }
            throw RelayError("registration HTTP ${exchange.status}")
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

/// The minimal HTTP/1.1-over-TLS client the pinned-edge routes ride — the
/// piece the 1.5.0 comment declared impossible and never shipped. The TCP
/// connection goes where `connectAddress` says (a pinned IP skips the
/// resolver entirely), while the TLS layer keeps the API's name for SNI and
/// certificate verification: `SSLSocketFactory.createSocket(socket, host,
/// 443, true)` plus the HTTPS endpoint-identification algorithm is the whole
/// trick — the same construction HttpsURLConnection itself uses behind a
/// tunneling proxy. HTTP/1.1 with `Connection: close` keeps the framing
/// trivial; Cloudflare's registration API answers small JSON on it.
internal object PinnedHttp {

    /// One parsed exchange — status, lower-cased headers (first occurrence
    /// wins), and the decoded body bytes.
    internal class Exchange(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String): String? = headers[name.lowercase()]
        fun bodyText(): String = String(body, Charsets.UTF_8)
    }

    /// The full request — TCP (optionally through a SOCKS5 proxy, hostname
    /// unresolved so the node resolves remotely), TLS, one HTTP/1.1 exchange,
    /// response parsed. Throws on any transport/TLS failure.
    internal fun exchange(
        connectAddress: String,
        host: String,
        method: String,
        path: String,
        body: String,
        token: String?,
        proxy: Proxy?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Exchange {
        val plain = if (proxy != null) Socket(proxy) else Socket()
        try {
            // Through SOCKS the hostname must travel unresolved — an
            // InetSocketAddress(host, port) would be resolved LOCALLY first
            // and the poisoned answer, not the name, would reach the proxy.
            val target = if (proxy != null) InetSocketAddress.createUnresolved(host, 443)
            else InetSocketAddress(connectAddress, 443)
            plain.connect(target, connectTimeoutMs)
            plain.soTimeout = readTimeoutMs

            // getDefault() is declared to return the SUPERtype SocketFactory
            // (a historical API wart) — the wrapped-socket overload with SNI
            // lives on SSLSocketFactory, so the factory itself is cast first.
            val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
            val ssl = factory.createSocket(plain, host, 443, true) as javax.net.ssl.SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            ssl.startHandshake()

            // Closing a Socket's OutputStream closes the SOCKET — so the
            // request stream is written and flushed, never closed here; the
            // response must still be read over the same connection.
            val out = ssl.getOutputStream()
            out.write(requestBytes(host, method, path, body, token))
            out.flush()
            val raw = ssl.getInputStream().use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buffer.write(chunk, 0, n)
                }
                buffer.toByteArray()
            }
            ssl.close()
            return parse(raw)
        } catch (e: Exception) {
            runCatching { plain.close() }
            throw e
        }
    }

    /// The exact header set the API is picky about, `Connection: close` for
    /// EOF-framed bodies, Content-Length always explicit.
    internal fun requestBytes(host: String, method: String, path: String, body: String, token: String?): ByteArray {
        val payload = body.toByteArray(Charsets.UTF_8)
        return buildString {
            append("$method $path HTTP/1.1\r\n")
            append("Host: $host\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n")
            append("Accept: application/json\r\n")
            append("User-Agent: WARP for Android\r\n")
            append("CF-Client-Version: a-6.35-4471\r\n")
            if (token != null) append("Authorization: Bearer $token\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8) + payload
    }

    /// Parse a full response (read to EOF). Status line → code, headers,
    /// body per Content-Length / chunked / to-EOF.
    internal fun parse(raw: ByteArray): Exchange {
        val text = String(raw, Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n")
        val head = if (split >= 0) text.substring(0, split) else text
        val rest = if (split >= 0) raw.copyOfRange(split + 4, raw.size) else ByteArray(0)

        val statusLine = head.lineSequence().firstOrNull()
            ?: throw java.io.IOException("empty HTTP response")
        val parts = statusLine.split(" ")
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) {
            throw java.io.IOException("not an HTTP response: ${statusLine.take(40)}")
        }
        val status = parts[1].toIntOrNull()
            ?: throw java.io.IOException("bad HTTP status: $statusLine")

        val headers = LinkedHashMap<String, String>()
        head.lineSequence().drop(1).forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) {
                val key = line.substring(0, i).trim().lowercase()
                headers.putIfAbsent(key, line.substring(i + 1).trim())
            }
        }

        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
                decodeChunked(rest)
            else -> {
                val len = headers["content-length"]?.toIntOrNull()
                if (len != null && len in 0..rest.size) rest.copyOf(len) else rest
            }
        }
        return Exchange(status, headers, body)
    }

    private fun decodeChunked(raw: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < raw.size) {
            var j = i
            while (j < raw.size && raw[j] != '\r'.code.toByte()) j++
            val size = String(raw, i, j - i, Charsets.ISO_8859_1).substringBefore(';')
                .trim().toIntOrNull(16) ?: break
            if (size == 0) break
            var k = j + 2 // skip CRLF after the size line
            val end = (k + size).coerceAtMost(raw.size)
            out.write(raw, k, end - k)
            i = (end + 2).coerceAtMost(raw.size) // skip CRLF after the chunk
        }
        return out.toByteArray()
    }
}
