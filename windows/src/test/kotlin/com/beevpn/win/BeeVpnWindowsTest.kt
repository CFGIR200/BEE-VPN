package com.beevpn.win

import com.beevpn.win.license.BeeCrypto
import com.beevpn.win.shard.ShardImport
import com.beevpn.win.shard.ShardType
import com.beevpn.win.warp.WarpProvisioner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

class BeeCryptoTest {

    /// Mint a real v1.<payload>.<sig> token with a real keypair — the exact
    /// shape license-worker.js issues — then verify it through the same path
    /// the app uses.
    private fun mintToken(payloadJson: String, privateKey: java.security.PrivateKey): String {
        val payload = payloadJson.toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(privateKey)
        sig.update(payload)
        val sigRaw = sig.sign()
        val b64url = Base64.getUrlEncoder().withoutPadding()
        return "v1." + b64url.encodeToString(payload) + "." + b64url.encodeToString(sigRaw)
    }

    private fun rawKeyPair(): Pair<java.security.PrivateKey, ByteArray> {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()
        // Raw 32 bytes = trailing 32 bytes of the PKCS#8 / X.509 encodings.
        val privEnc = kp.private.encoded
        val pubEnc = kp.public.encoded
        return Pair(
            kp.private,
            pubEnc.copyOfRange(pubEnc.size - 32, pubEnc.size),
        )
    }

    @Test
    fun `valid token verifies and carries the entitlement`() {
        val (priv, pubRaw) = rawKeyPair()
        val pubB64 = Base64.getEncoder().encodeToString(pubRaw)
        val token = mintToken(
            """{"s":"BEE-TEST-1234-5678-9ABC","k":"code","p":"m3","i":"abc123","e":4102444800,"t":1700000000}""",
            priv,
        )
        val e = BeeCrypto.verifyToken(token, pubB64, "abc123")
        assertNotNull(e)
        assertEquals("BEE-TEST-1234-5678-9ABC", e.subject)
        assertEquals("m3", e.plan)
        assertEquals("code", e.kind)
    }

    @Test
    fun `foreign device is rejected`() {
        val (priv, pubRaw) = rawKeyPair()
        val pubB64 = Base64.getEncoder().encodeToString(pubRaw)
        val token = mintToken(
            """{"s":"BEE-X","k":"code","p":"m1","i":"device-A","e":4102444800,"t":1}""",
            priv,
        )
        assertNull(BeeCrypto.verifyToken(token, pubB64, "device-B"))
    }

    @Test
    fun `expired token is rejected`() {
        val (priv, pubRaw) = rawKeyPair()
        val pubB64 = Base64.getEncoder().encodeToString(pubRaw)
        val token = mintToken(
            """{"s":"BEE-X","k":"code","p":"m1","i":"dev","e":1000,"t":1}""",
            priv,
        )
        assertNull(BeeCrypto.verifyToken(token, pubB64, "dev"))
    }

    @Test
    fun `tampered payload is rejected`() {
        val (priv, pubRaw) = rawKeyPair()
        val pubB64 = Base64.getEncoder().encodeToString(pubRaw)
        val token = mintToken(
            """{"s":"BEE-X","k":"code","p":"m1","i":"dev","e":4102444800,"t":1}""",
            priv,
        )
        val parts = token.split(".")
        val evilPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"s":"BEE-HACKED","k":"code","p":"m12","i":"dev","e":4102444800,"t":1}""".toByteArray())
        assertNull(BeeCrypto.verifyToken("v1.$evilPayload.${parts[2]}", pubB64, "dev"))
    }

    @Test
    fun `malformed tokens are rejected`() {
        val pubB64 = Base64.getEncoder().encodeToString(ByteArray(32))
        assertNull(BeeCrypto.verifyToken("garbage", pubB64, "dev"))
        assertNull(BeeCrypto.verifyToken("v2.a.b", pubB64, "dev"))
        assertNull(BeeCrypto.verifyToken("v1.!!!.!!", pubB64, "dev"))
    }
}

class ShardImportTest {

    @Test
    fun `vless reality parses end to end`() {
        val p = ShardImport.parseOne(
            "vless://8f2c7c1e-2b3d-4e5f-6a7b-8c9d0e1f2a3b@example.com:443?encryption=none&security=reality" +
                "&sni=www.microsoft.com&fp=chrome&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=6ba85179" +
                "&type=tcp&flow=xtls-rprx-vision#RealityNode",
        )
        assertNotNull(p)
        assertEquals(ShardType.VLESS, p!!.configType)
        assertEquals("example.com", p.server)
        assertEquals("443", p.serverPort)
        assertEquals("reality", p.security)
        assertEquals("xtls-rprx-vision", p.flow)
        assertEquals("RealityNode", p.name)
        assertEquals("SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc", p.publicKey)
    }

    @Test
    fun `shadowsocks form A parses`() {
        val inner = Base64.getEncoder().encodeToString("aes-256-gcm:hunter2".toByteArray())
        val p = ShardImport.parseOne("ss://$inner@1.2.3.4:8388#MySS")
        assertNotNull(p)
        assertEquals(ShardType.SHADOWSOCKS, p!!.configType)
        assertEquals("aes-256-gcm", p.method)
        assertEquals("hunter2", p.password)
        assertEquals("8388", p.serverPort)
    }

    @Test
    fun `hysteria2 parses with obfs`() {
        val p = ShardImport.parseOne("hysteria2://pw123@5.6.7.8:443?sni=cdn.example.com&insecure=1&obfs=salamander&obfs-password=obfsPass#HY2")
        assertNotNull(p)
        assertEquals(ShardType.HYSTERIA2, p!!.configType)
        assertEquals("pw123", p.password)
        assertEquals("obfsPass", p.obfsPassword)
        assertEquals(true, p.insecure)
    }

    @Test
    fun `anytls parses`() {
        val p = ShardImport.parseOne("anytls://secretpass@9.9.9.9:443?sni=web.example.com&insecure=0#AnyNode")
        assertNotNull(p)
        assertEquals(ShardType.ANYTLS, p!!.configType)
        assertEquals("secretpass", p.password)
        assertEquals("web.example.com", p.sni)
    }

    @Test
    fun `base64 batch body decodes and skips junk lines`() {
        val body = """
            # comment
            vless://uuid-a@h1.example.com:443?security=tls&type=ws&path=%2Fws#A
            not-a-scheme-line
            trojan://pass@h2.example.com:443?sni=h2.example.com#B
        """.trimIndent()
        val b64 = Base64.getEncoder().encodeToString(body.toByteArray())
        val list = ShardImport.parseBatch(b64)
        assertEquals(2, list.size)
    }
}

class WgQuickTest {

    @Test
    fun `manual wg-quick parses into an identity`() {
        val cfg = """
            [Interface]
            PrivateKey = qPlLVCHdcA7qqgDCpBHveOmJHN0OmJ0dRlG3u9vGH1U=
            Address = 172.16.0.2/32, 2606:4700:110:8a62:2b8c:291b:9d5a:7c3b/128
            DNS = 1.1.1.1
            MTU = 1280

            [Peer]
            PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = engage.cloudflareclient.com:500
        """.trimIndent()
        val id = WarpProvisioner.parseWGQuick(cfg)
        assertNotNull(id)
        assertEquals("172.16.0.2", id!!.addressV4)
        assertEquals("engage.cloudflareclient.com:500", id.endpoint)
        assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", id.peerPublicKey)
    }

    @Test
    fun `wg-quick without keys is rejected`() {
        assertNull(WarpProvisioner.parseWGQuick("[Interface]\nAddress=10.0.0.1/32\n"))
    }
}
