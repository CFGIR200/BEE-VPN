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
data class WarpIdentity(
    val deviceId: String,
    val apiToken: String,
    val privateKey: String,     // X25519 raw key, standard base64 (wg-quick form)
    val peerPublicKey: String,  // peers[0].public_key
    val addressV4: String,      // interface.addresses.v4 (e.g. 172.16.0.2)
    val addressV6: String,
    val endpoint: String,       // WireGuard UDP endpoint host:port
) {
    fun asJson(): String = JSONObject()
        .put("deviceId", deviceId).put("apiToken", apiToken)
        .put("privateKey", privateKey).put("peerPublicKey", peerPublicKey)
        .put("addressV4", addressV4).put("addressV6", addressV6)
        .put("endpoint", endpoint).toString()

    companion object {
        fun fromJson(s: String): WarpIdentity? = try {
            val o = JSONObject(s)
            WarpIdentity(
                o.getString("deviceId"), o.getString("apiToken"),
                o.getString("privateKey"), o.getString("peerPublicKey"),
                o.getString("addressV4"), o.optString("addressV6", ""),
                o.optString("endpoint", "engage.cloudflareclient.com:2408"),
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

    private const val REGISTER_URL = "https://api.cloudflareclient.com/v0a4471/reg"
    const val DEFAULT_ENDPOINT = "engage.cloudflareclient.com:2408"

    /// Wire ports the Ptech-quality WARP surfaces actually answer on — the
    /// same ladder CoreConfig.kt uses.
    val portLadder = listOf(2408, 500, 1701, 4500)

    private const val KEYCHAIN_KEY = "warp.identity"

    class ProvisionError(message: String) : Exception(message)

    fun saved(): WarpIdentity? = SecureStore.get(KEYCHAIN_KEY)?.let { WarpIdentity.fromJson(it) }

    fun save(identity: WarpIdentity) = SecureStore.put(KEYCHAIN_KEY, identity.asJson())

    fun purge() = SecureStore.remove(KEYCHAIN_KEY)

    /// Registers a fresh identity. Throws a Persian, buyer-readable error on
    /// failure — the message ends up on the Home screen, not in a log.
    suspend fun register(throughSocksPort: Int? = null): WarpIdentity = withContext(Dispatchers.IO) {
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

        val builder = HttpRequest.newBuilder()
            .uri(URI.create(REGISTER_URL))
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
            throw ProvisionError(
                if (throughSocksPort == null)
                    "ثبت‌نام WARP ممکن نشد — اپراتور مسیر api.cloudflareclient.com را می‌بندد؟ (${e.message ?: "بدون جزئیات"})"
                else "ثبت‌نام WARP از مسیر SHARD هم ناموفق بود (${e.message ?: "بدون جزئیات"})"
            )
        }
        if (resp.statusCode() !in 200..299) {
            throw ProvisionError("سرور WARP کد ${resp.statusCode()} برگرداند — کمی بعد یا با تنظیم دستی تلاش کن")
        }
        val obj = try { JSONObject(resp.body()) } catch (_: Exception) { throw ProvisionError("پاسخ ثبت‌نام WARP ناقص بود — دوباره تلاش کن") }
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
        val identity = WarpIdentity(
            deviceId = deviceId,
            apiToken = apiToken,
            privateKey = Base64.getEncoder().encodeToString(privateRaw),
            peerPublicKey = peerKey,
            addressV4 = v4,
            addressV6 = addresses?.optString("v6", "") ?: "",
            endpoint = DEFAULT_ENDPOINT,
        )
        save(identity)
        identity
    }

    /// Generates a fresh X25519 keypair and returns (privateRaw, publicRaw) —
    /// both the trailing 32 raw bytes of their JDK encodings (PKCS#8 / X.509),
    /// ready for the standard base64 wg-quick representation.
    private fun newX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val kpg = KeyPairGenerator.getInstance("X25519")
        kpg.initialize(256, SecureRandom())
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
        return WarpIdentity("manual", "", privateKey, peerKey, v4, v6, endpoint)
    }
}
