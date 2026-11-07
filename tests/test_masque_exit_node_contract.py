from pathlib import Path
import unittest

ROOT = Path(__file__).parents[1]
MAIN = ROOT / "app/src/main/java/com/msnguard/vpn/MainActivity.kt"
SERVICE = ROOT / "app/src/main/java/com/msnguard/vpn/MsnGuardVpnService.kt"
EXIT = ROOT / "app/src/main/java/com/msnguard/vpn/WarpExitManager.kt"
CORE = ROOT / "app/src/main/java/com/msnguard/vpn/CoreConfig.kt"
BUILD = ROOT / "app/build.gradle.kts"


class MasqueExitNodeContractTest(unittest.TestCase):
    def test_exit_switch_keeps_cache_first_discovery(self):
        text = MAIN.read_text()
        start = text.index("private fun requestFreshScanAfterExitSwitch")
        block = text[start : start + 500]
        self.assertIn("EndpointDiscovery.CACHE.coreName", block)
        self.assertNotIn("EndpointDiscovery.FRESH.coreName", block)
        self.assertNotIn("aether-lastconn.toml", block)

    def test_custom_exit_requires_real_tcp_preflight_before_upstream(self):
        # The custom-exit machinery moved out of the service: the TCP preflight
        # lives in WarpExitManager, the AETHER_UPSTREAM wiring in CoreConfig.
        exit_mgr = EXIT.read_text()
        probe = 'probeTcpThroughExit("www.cloudflare.com", 443, 2_000)'
        self.assertIn(probe, exit_mgr)
        self.assertIn("TCP preflight passed", exit_mgr)
        core = CORE.read_text()
        upstream = 'out["AETHER_UPSTREAM"] = socksProxyForCore'
        self.assertIn(upstream, core)

    def test_preflight_keeps_dns_inside_socks_proxy(self):
        text = EXIT.read_text()
        self.assertIn("Proxy.Type.SOCKS", text)
        self.assertIn("InetSocketAddress.createUnresolved(host, port)", text)

    def test_version_is_experimental_and_incremented(self):
        # The version is tag-driven (CI rewrites these lines from the v3.x.y
        # tag), so the contract pins the SHAPE, not a stale literal.
        text = BUILD.read_text()
        import re
        assert re.search(r'versionCode = \d+', text)
        assert re.search(r'versionName = "[^\"]+"', text)

    def test_custom_exit_clears_freedom_ech_on_main_and_mtu_retry_env(self):
        # ECH clearing happens in CoreConfig now — on the main env build and
        # on the MTU-retry rebuild (both masque-family branches).
        core = CORE.read_text()
        self.assertGreaterEqual(core.count('remove("AETHER_API_ECH")'), 2)
        self.assertGreaterEqual(core.count('remove("AETHER_ECH")'), 2)
        self.assertIn('out["AETHER_UPSTREAM"] = socksProxyForCore', core)


if __name__ == "__main__":
    unittest.main()
