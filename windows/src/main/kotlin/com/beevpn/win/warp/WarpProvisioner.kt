package com.beevpn.win.warp

import com.beevpn.win.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.Locale

/// One registered WARP identity — the same shape IdentityProvisioner.kt
/// persists on Android (aether.toml) and WarpProvisioner.swift on iOS.
///
/// `clientIdB64` is the registration API's `config.client_id` — three bytes
/// Cloudflare requires INSIDE the WireGuard init/response packets (the
/// "reserved" field). The Rust core injects them at pkt[1..4]
/// (core/aether/src/wireguard.rs inject_client_id); the xray wireguard
/// outbound needs the same bytes as peers[0].reserved. Without them the
/// WARP endpoint silently drops every handshake — the tunnel comes up but
/// not a single byte leaves: «تونل بالا آمد اما خروجی ترافیک پاس نمی‌دهد».
data class WarpIdentity(
    val deviceId: String,
    val apiToken: String,
    val privateKey: String,     // X25519 raw key, standard base64 (wg-quick form)
    val peerPublicKey: String,  // peers[0].public_key
    val addressV4: String,      // interface.addresses.v4 (e.g. 172.16.0.2)
    val addressV6: String,
    val endpoint: String,       // WireGuard UDP endpoint host:port
    val clientIdB64: String = "", // config.client_id — the reserved bytes (base64)
) {
    fun asJson(): String = JSONObject()
        .put("deviceId", deviceId).put("apiToken", apiToken)
        .put("privateKey", privateKey).put("peerPublicKey", peerPublicKey)
        .put("addressV4", addressV4).put("addressV6", addressV6)
        .put("endpoint", endpoint)
        .put("clientIdB64", clientIdB64)
        .toString()

    /// The three reserved bytes, or null when the identity has none (manual
    /// wg-quick) or the blob is malformed. xray peers[].reserved wants a
    /// numeric array — never emit a wrong length.
    fun reservedBytes(): IntArray? {
        if (clientIdB64.isBlank()) return null
        return try {
            val raw = Base64.getDecoder().decode(clientIdB64)
            if (raw.size != 3) null else IntArray(3) { raw[it].toInt() and 0xFF }
        } catch (_: Exception) { null }
    }

    companion object {
        fun fromJson(s: String): WarpIdentity? = try {
            val o = JSONObject(s)
            WarpIdentity(
                o.getString("deviceId"), o.getString("apiToken"),
                o.getString("privateKey"), o.getString("peerPublicKey"),
                o.getString("addressV4"), o.optString("addressV6", ""),
                o.optString("endpoint", "engage.cloudflareclient.com:2408"),
                o.optString("clientIdB64", ""),
            )
        } catch (_: Exception) { null }
    }
}

/// Registers WARP identities and turns them into WireGuard outbounds.
///
/// The desktop app speaks to the registration API directly, with a SOCKS
/// fallback that rides a running SHARD session — the same orchestration
/// IdentityProvisioner.kt does when the carrier poisons
/// api.cloudflareclient.com. The manual wg-quick config in the Advanced tab
/// remains the last-resort escape hatch, exactly like iOS.
object WarpProvisioner {

    /// The registration API under BOTH names Cloudflare serves it on. On
    /// carriers that poison api.cloudflareclient.com the devices host often
    /// still resolves (the same route Android's IdentityRelayClient walks:
    /// api.devices.cloudflare.com → api.cloudflareclient.com).
    private val API_HOSTS = listOf("api.devices.cloudflare.com", "api.cloudflareclient.com")
    private const val REGISTER_PATH = "/v0a4471/reg"
    const val DEFAULT_ENDPOINT = "engage.cloudflareclient.com:2408"

    /// Wire ports the Ptech-quality WARP surfaces actually answer on — the
    /// same ladder CoreConfig.kt uses.
    val portLadder = listOf(2408, 500, 1701, 4500)

    private const val KEYCHAIN_KEY = "warp.identity"

    class ProvisionError(message: String) : Exception(message)

    fun saved(): WarpIdentity? = SecureStore.get(KEYCHAIN_KEY)?.let { WarpIdentity.fromJson(it) }

    fun save(identity: WarpIdentity) = SecureStore.put(KEYCHAIN_KEY, identity.asJson())

    fun purge() = SecureStore.remove(KEYCHAIN_KEY)

    // -------------------------------------------------- API reachability

    /// Android's accountApiReachable contract, desktop edition: one POST probe
    /// decides whether the device's own link can reach the registration API at
    /// all, and a negative answer is cached for 5 minutes so the Auto-Scan
    /// ladder does not pay the dead timeout on every rung. A 4xx still proves
    /// the route is open — only total transport failure counts as blocked.
    @Volatile private var apiBlockedSince = 0L
    private const val API_BLOCKED_CACHE_MS = 5 * 60_000L
    private const val PROBE_TIMEOUT_MS = 8_000

    /// True while a cached "blocked" verdict is fresh — the caller skips the
    /// doomed direct registration and goes straight to the engine's ECH path.
    fun apiRecentlyBlocked(): Boolean =
        apiBlockedSince != 0L &&
            System.currentTimeMillis() - apiBlockedSince < API_BLOCKED_CACHE_MS

    fun clearApiBlockedCache() { apiBlockedSince = 0L }

    /// The probe — a real POST to /reg (a plain GET answers on carriers that
    /// still drop the actual registration, so the probe must match its shape).
    private fun probeApiBlocked(): Boolean {
        for (host in API_HOSTS) {
            try {
                val body = JSONObject()
                    .put("install_id", "").put("fcm_token", "")
                    .put("tos", java.time.Instant.now().toString().replace(Regex("\\.\\d+Z$"), "Z"))
                    .put("model", "Windows").put("serial_number", "unknown")
                    .put("locale", "en_US").put("key_type", "curve25519")
                    .put("tunnel_type", "wireguard")
                val conn = URI("https", null, host, 443, REGISTER_PATH, null, null)
                    .toURL().openConnection() as javax.net.ssl.HttpsURLConnection
                conn.connectTimeout = PROBE_TIMEOUT_MS
                conn.readTimeout = PROBE_TIMEOUT_MS
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "okhttp/3.12.1")
                conn.setRequestProperty("CF-Client-Version", "a-6.41-2158")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                // Any HTTP status means the route works — even 400/429.
                val reachable = conn.responseCode in 200..599
                conn.disconnect()
                if (reachable) {
                    apiBlockedSince = 0L
                    return false
                }
            } catch (_: Exception) {
                // try the next host
            }
        }
        apiBlockedSince = System.currentTimeMillis()
        return true
    }

    /// Registers a fresh identity. Throws a Persian, buyer-readable error on
    /// failure — the message ends up on the Home screen, not in a log.
    ///
    /// Host ladder: api.devices.cloudflare.com first (usually unfiltered),
    /// then api.cloudflareclient.com. On a network the probe already measured
    /// as blocked the call refuses fast so the engine's ECH-backed register
    /// takes over instead of burning another 30 s on a dead route.
    suspend fun register(throughSocksPort: Int? = null): WarpIdentity = withContext(Dispatchers.IO) {
        if (throughSocksPort == null) {
            // Fresh verdict or the 5-minute cached one: a measured-blocked
            // route means the engine's ECH register is the only path — refuse
            // fast instead of burning ~60 s on two dead hosts.
            if (apiRecentlyBlocked() || probeApiBlocked()) {
                throw ProvisionError("مسیر ثبت‌نام WARP روی این شبکه مسدود است — از مسیر رمزگذاری‌شده (ECH) تلاش می‌شود")
            }
        }
        // newX25519KeyPair returns (privateRaw, publicRaw), both 32 raw bytes.
        val (privateRaw, publicRaw) = newX25519KeyPair()
        val publicKeyB64 = Base64.getEncoder().encodeToString(publicRaw)

        val body = JSONObject()
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", java.time.Instant.now().toString().replace(Regex("\\.\\d+Z$"), "Z"))
            .put("model", "Windows")
            .put("serial_number", "unknown")
            .put("locale", Locale.getDefault().toString())
            .put("key_type", "curve25519")
            .put("tunnel_type", "wireguard")
            // The client's own public key — the API refuses a registration
            // without it ("key" is what the endpoint will hand our packets to).
            .put("key", publicKeyB64)

        var lastError: Exception? = null
        for (host in API_HOSTS) {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create("https://$host$REGISTER_PATH"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                // The exact headers the Android registration uses — the API is picky.
                .header("User-Agent", "okhttp/3.12.1")
                .header("CF-Client-Version", "a-6.41-2158")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))

            val clientBuilder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12))
            if (throughSocksPort != null) {
                clientBuilder.proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", throughSocksPort)))
            }

            val resp: HttpResponse<String> = try {
                clientBuilder.build().send(builder.build(), HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                lastError = e
                continue   // next host
            }
            if (resp.statusCode() !in 200..299) {
                // A hard 4xx answer means THIS host answered but refused — the
                // route works, so do not mask it with the other host.
                if (resp.statusCode() == 429) { lastError = ProvisionError("سرور WARP کد ۴۲۹ برگرداند — کمی بعد تلاش کن"); continue }
                throw ProvisionError("سرور WARP کد ${resp.statusCode()} برگرداند — کمی بعد یا با تنظیم دستی تلاش کن")
            }
            val identity = parseRegistration(resp.body(), privateRaw)
            save(identity)
            apiBlockedSince = 0L
            return@withContext identity
        }
        // Both hosts failed at the transport level — mark the carrier blocked
        // so the next attempt skips straight to the engine's ECH register.
        if (throughSocksPort == null) apiBlockedSince = System.currentTimeMillis()
        throw ProvisionError(
            if (throughSocksPort == null)
                "ثبت‌نام WARP ممکن نشد — اپراتور مسیر api.cloudflareclient.com را می‌بندد؟ (${lastError?.message ?: "بدون جزئیات"})"
            else "ثبت‌نام WARP از مسیر SHARD هم ناموفق بود (${lastError?.message ?: "بدون جزئیات"})"
        )
    }

    /// The registration response → identity. Split out so both hosts share it.
    private fun parseRegistration(body: String, privateRaw: ByteArray): WarpIdentity {
        val obj = try { JSONObject(body) } catch (_: Exception) { throw ProvisionError("پاسخ ثبت‌نام WARP ناقص بود — دوباره تلاش کن") }
        val deviceId = obj.optString("id", "")
        val apiToken = obj.optString("token", "")
        val config = obj.optJSONObject("config")
        val interfaceObj = config?.optJSONObject("interface")
        val addresses = interfaceObj?.optJSONObject("addresses")
        val v4 = addresses?.optString("v4", "") ?: ""
        val peers = config?.optJSONArray("peers")
        val peerKey = if (peers != null && peers.length() > 0) peers.getJSONObject(0).optString("public_key", "") else ""
        if (deviceId.isEmpty() || apiToken.isEmpty() || v4.isEmpty() || peerKey.isEmpty()) {
            throw ProvisionError("پاسخ ثبت‌نام WARP ناقص بود — دوباره تلاش کن")
        }
        // The reserved bytes: Cloudflare drops handshakes that do not carry
        // them (the aether core decodes the same field — account.rs L779+).
        val clientIdB64 = config?.optString("client_id", "") ?: ""
        if (clientIdB64.isBlank()) {
            WarpProvisionerLog.warn("registration returned no client_id — WARP egress will likely fail")
        }
        return WarpIdentity(
            deviceId = deviceId,
            apiToken = apiToken,
            privateKey = Base64.getEncoder().encodeToString(privateRaw),
            peerPublicKey = peerKey,
            addressV4 = v4,
            addressV6 = addresses?.optString("v6", "") ?: "",
            endpoint = DEFAULT_ENDPOINT,
            clientIdB64 = clientIdB64,
        )
    }

    /// Generates a fresh X25519 keypair and returns (privateRaw, publicRaw) —
    /// both the trailing 32 raw bytes of their JDK encodings (PKCS#8 / X.509),
    /// ready for the standard base64 wg-quick representation.
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

    /// Builds the wg-quick configuration (display + manual mode), byte-for-byte
    /// the shape WarpProvisioner.swift produces.
    fun buildWGQuick(identity: WarpIdentity): String {
        fun withPrefix(addr: String, prefix: String) = if (addr.contains("/")) addr else addr + prefix
        val lines = mutableListOf(
            "[Interface]",
            "PrivateKey = ${identity.privateKey}",
        )
        var addrs = withPrefix(identity.addressV4, "/32")
        if (identity.addressV6.isNotEmpty()) addrs += ", " + withPrefix(identity.addressV6, "/128")
        lines.add("Address = $addrs")
        lines.add("DNS = 1.1.1.1, 1.0.0.1")
        lines.add("MTU = 1280")
        lines.add("")
        lines.add("[Peer]")
        lines.add("PublicKey = ${identity.peerPublicKey}")
        lines.add("AllowedIPs = 0.0.0.0/0, ::/0")
        lines.add("Endpoint = ${identity.endpoint}")
        lines.add("PersistentKeepalive = 25")
        return lines.joinToString("\n")
    }

    /// Parses a manual wg-quick config (the Advanced-tab escape hatch) into
    /// the fields the xray wireguard outbound needs. Returns null on a bad file.
    fun parseWGQuick(text: String): WarpIdentity? {
        var privateKey = ""
        var address = ""
        var peerKey = ""
        var endpoint = DEFAULT_ENDPOINT
        var section = ""
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) { section = line.trim('[', ']'); continue }
            val i = line.indexOf('=')
            if (i <= 0) continue
            val k = line.substring(0, i).trim()
            val v = line.substring(i + 1).trim()
            when (section) {
                "Interface" -> {
                    if (k.equals("PrivateKey", true)) privateKey = v
                    if (k.equals("Address", true)) address = v
                }
                "Peer" -> {
                    if (k.equals("PublicKey", true)) peerKey = v
                    if (k.equals("Endpoint", true)) endpoint = v
                }
            }
        }
        if (privateKey.isEmpty() || peerKey.isEmpty()) return null
        val v4 = address.split(",").map { it.trim() }.firstOrNull { it.contains(".") }?.substringBefore("/") ?: "172.16.0.2"
        val v6 = address.split(",").map { it.trim() }.firstOrNull { it.contains(":") }?.substringBefore("/") ?: ""
        // wg-quick text carries no client_id — a manual WARP config without
        // reserved bytes only works on non-Cloudflare WireGuard endpoints.
        return WarpIdentity("manual", "", privateKey, peerKey, v4, v6, endpoint, "")
    }
}

/// Tiny log shim so the register path can warn without pulling more of the
/// engine stack into every caller.
private object WarpProvisionerLog {
    fun warn(msg: String) {
        try {
            java.io.File(com.beevpn.win.AppPaths.logsDir, "warp.log")
                .appendText("[provisioner] $msg\n")
        } catch (_: Exception) {}
    }
}
