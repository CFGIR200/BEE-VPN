package com.beevpn.win

import com.beevpn.win.engine.EngineConfig
import com.beevpn.win.warp.WarpIdentity
import com.beevpn.win.warp.WarpProvisioner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

/// The WARP egress suite — the regression tests for the «اتصال ناموفق»
/// buyers saw when the tunnel came up but Cloudflare answered nothing:
/// the registration's client_id (three reserved bytes) was dropped on the
/// floor instead of riding the wireguard outbound as peers[0].reserved.
/// Cloudflare silently discards reserved-less handshakes — no error, no
/// egress, exactly the screenshot bug.
class WarpEgressTest {

    private fun identity(clientIdB64: String = "gKD6") = WarpIdentity(
        deviceId = "device-1",
        apiToken = "token-1",
        privateKey = "eCtXmP0s3cr3tK3yBase64Value0000000000=",
        peerPublicKey = "b585fylzF0CjlS1eNh8pD0Xx0yO6RlUeC7hGpUlQqWU=",
        addressV4 = "172.16.0.2/32",
        addressV6 = "2606:4700:110:8a62::/128",
        endpoint = "engage.cloudflareclient.com:2408",
        clientIdB64 = clientIdB64,
    )

    // ------------------------------------------------------- identity shape

    @Test
    fun `identity json round-trips the client_id`() {
        val saved = identity().asJson()
        val loaded = WarpIdentity.fromJson(saved)
        assertNotNull(loaded)
        assertEquals("gKD6", loaded.clientIdB64)
        assertEquals(identity().privateKey, loaded.privateKey)
        assertEquals(identity().endpoint, loaded.endpoint)
    }

    @Test
    fun `legacy identity without client_id still loads`() {
        // what 1.1.1 wrote into the SecureStore — must keep loading so the
        // migration path (purge + re-register) can see it
        val legacy = JSONObject()
            .put("deviceId", "d").put("apiToken", "t")
            .put("privateKey", "k").put("peerPublicKey", "p")
            .put("addressV4", "172.16.0.2").put("addressV6", "")
            .put("endpoint", "engage.cloudflareclient.com:2408")
            .toString()
        val loaded = WarpIdentity.fromJson(legacy)
        assertNotNull(loaded)
        assertEquals("", loaded.clientIdB64)
        assertNull(loaded.reservedBytes())
    }

    // ------------------------------------------------------- reserved bytes

    @Test
    fun `reservedBytes decodes three bytes`() {
        // base64 "gKD6" == 0x80 0xa0 0xfa — the shape the registration API
        // returns in config.client_id
        val reserved = identity().reservedBytes()
        assertNotNull(reserved)
        assertEquals(3, reserved.size)
        assertEquals(0x80, reserved[0])
        assertEquals(0xA0, reserved[1])
        assertEquals(0xFA, reserved[2])
    }

    @Test
    fun `reservedBytes rejects wrong lengths and garbage`() {
        assertNull(identity("").reservedBytes(), "empty client_id → no reserved")
        assertNull(identity("AAAAAAAA").reservedBytes(), "8-char blob decodes to 6 bytes → wrong length")
        assertNull(identity("!!!not base64!!!").reservedBytes())
    }

    // ------------------------------------------------------- xray outbound

    @Test
    fun `warpExit emits the reserved array on the peer`() {
        val exit = EngineConfig.warpExit(identity())
        assertEquals("bee-exit", exit.getString("tag"))
        val peer = exit.getJSONObject("settings").getJSONArray("peers").getJSONObject(0)
        val reserved = peer.getJSONArray("reserved")
        assertEquals(3, reserved.length())
        assertEquals(0x80, reserved.getInt(0))
        assertEquals(0xA0, reserved.getInt(1))
        assertEquals(0xFA, reserved.getInt(2))
        assertEquals("engage.cloudflareclient.com:2408", peer.getString("endpoint"))
    }

    @Test
    fun `warpExit omits reserved when the identity has no client_id`() {
        // manual wg-quick identities carry none — the field must stay absent,
        // never an empty or zeroed array (xray would inject broken bytes)
        val exit = EngineConfig.warpExit(identity(clientIdB64 = ""))
        val peer = exit.getJSONObject("settings").getJSONArray("peers").getJSONObject(0)
        assertFalse(peer.has("reserved"))
    }

    // ------------------------------------------------------- endpoint ladder

    @Test
    fun `wg-quick parser keeps manual identities reserved-free`() {
        val wg = """
            [Interface]
            PrivateKey = eCtXmP0s3cr3tK3yBase64Value0000000000=
            Address = 172.16.0.2/32, 2606:4700:110:8a62::/128
            DNS = 1.1.1.1
            MTU = 1280

            [Peer]
            PublicKey = b585fylzF0CjlS1eNh8pD0Xx0yO6RlUeC7hGpUlQqWU=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 162.159.193.10:2408
        """.trimIndent()
        val parsed = WarpProvisioner.parseWGQuick(wg)
        assertNotNull(parsed)
        assertEquals("162.159.193.10:2408", parsed.endpoint)
        assertNull(parsed.reservedBytes())
    }

    @Test
    fun `port ladder covers the four known WARP UDP ports`() {
        assertEquals(listOf(2408, 500, 1701, 4500), WarpProvisioner.portLadder)
    }

    // ------------------------------------------------------- gate paste UX

    @Test
    fun `normalizeCode pulls the code out of a pasted invoice`() {
        val invoice = "سلام! لایسنس شما: BEE-AB2C-D3EF-GH45 — فعال‌سازی: eethe.app"
        assertEquals("BEE-AB2C-D3EF-GH45", com.beevpn.win.ui.normalizeCode(invoice))
    }

    @Test
    fun `normalizeCode uppercases and trims a bare code`() {
        assertEquals("BEE-AB2C-D3EF-GH45", com.beevpn.win.ui.normalizeCode("  bee-ab2c-d3ef-gh45 \n"))
    }

    @Test
    fun `normalizeCode tolerates en-dash and spaces`() {
        assertEquals("BEE-AB2C-D3EF-GH45", com.beevpn.win.ui.normalizeCode("BEE AB2C D3EF GH45"))
        assertEquals("BEE-AB2C-D3EF-GH45", com.beevpn.win.ui.normalizeCode("BEE–AB2C–D3EF–GH45"))
    }
}
