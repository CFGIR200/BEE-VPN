package com.beevpn.win

import com.beevpn.win.license.LicenseAPI
import com.beevpn.win.warp.WarpIdentity
import com.beevpn.win.warp.WarpProvisioner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// The buyer-facing Persian surface of the license flow — the QA round-trip
/// for every message the gate can print, so no raw English error ever leaks
/// onto the gate screen.
class LicenseApiMessageTest {

    @Test
    fun `every known worker error maps to persian`() {
        assertTrue(LicenseAPI.faMessage("unknown code").contains("وجود ندارد"))
        assertTrue(LicenseAPI.faMessage("invalid code format").contains("قالب"))
        assertTrue(LicenseAPI.faMessage("revoked").contains("باطل"))
        assertTrue(LicenseAPI.faMessage("expired").contains("به پایان رسیده"))
        assertTrue(LicenseAPI.faMessage("bound to another device").contains("دستگاه دیگری"))
        assertTrue(LicenseAPI.faMessage("wrong username or password").contains("اشتباه است"))
        assertTrue(LicenseAPI.faMessage("too many attempts, try later").contains("یک ساعت"))
    }

    @Test
    fun `unknown worker error still becomes readable`() {
        val m = LicenseAPI.faMessage("boom")
        assertTrue(m.startsWith("خطا از سرور"))
        assertTrue(m.contains("boom"))
    }
}

/// The wg-quick printer — the shape the Advanced tab shows and the manual
/// mode consumes; a regression here breaks hand-carried configs.
class WgQuickBuildTest {

    private val id = WarpIdentity(
        deviceId = "dev", apiToken = "", privateKey = "abc=",
        peerPublicKey = "peer=", addressV4 = "172.16.0.2", addressV6 = "",
        endpoint = "engage.cloudflareclient.com:2408",
    )

    @Test
    fun `address gets its prefix and the peer block is complete`() {
        val cfg = WarpProvisioner.buildWGQuick(id)
        assertTrue(cfg.contains("PrivateKey = abc="))
        assertTrue(cfg.contains("Address = 172.16.0.2/32"))
        assertTrue(cfg.contains("PublicKey = peer="))
        assertTrue(cfg.contains("AllowedIPs = 0.0.0.0/0, ::/0"))
        assertTrue(cfg.contains("Endpoint = engage.cloudflareclient.com:2408"))
        assertTrue(cfg.contains("PersistentKeepalive = 25"))
    }

    @Test
    fun `already-prefixed address is not doubled`() {
        val cfg = WarpProvisioner.buildWGQuick(id.copy(addressV4 = "10.7.0.2/32"))
        assertTrue(cfg.contains("Address = 10.7.0.2/32"))
        assertTrue(!cfg.contains("/32/32"))
    }
}

/// Persian digits + Jalali dates — the constants every screen prints.
class FaTest {

    @Test
    fun `digits convert`() {
        assertEquals("۱۲۳۴", faDigits("1234"))
        assertEquals("۱۲۳۴", faNum(1234))
        assertEquals("BEE-", faDigits("BEE-"))
    }

    @Test
    fun `jalali epoch zero is dey 1348`() {
        assertEquals("۱۳۴۸/۱۰/۱۱", jalaliString(0L))
    }

    @Test
    fun `jalali knows nowruz boundary`() {
        // 2026-11-06T00:00:00Z (epoch 1793923200) → Aban 15, 1405 (Tehran +3:30)
        assertEquals("۱۴۰۵/۰۸/۱۵", jalaliString(1793923200L))
    }

    @Test
    fun `human remainder speaks days and hours`() {
        val now = 1_000_000L
        assertEquals("۲ روز و ۵ ساعت", daysLeftHuman(now + 2 * 86_400 + 5 * 3_600, now))
        assertEquals("۷ ساعت", daysLeftHuman(now + 7 * 3_600, now))
        assertEquals("۰ دقیقه", daysLeftHuman(now - 100, now)) // already over
    }
}
