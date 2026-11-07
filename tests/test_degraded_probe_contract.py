"""The carrier-side health probe must not paint a native tunnel degraded."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = (ROOT / "app/src/main/java/com/msnguard/vpn/MainActivity.kt").read_text()
BUILD = (ROOT / "app/build.gradle.kts").read_text()

# Keep this a small source contract: it protects the exact branch that caused the
# Zeptun/Hev field symptom without pretending to reproduce Android networking.
carrier_branch = UI.split('"Health probe failed but it left over the carrier link', 1)[1].split('} else {', 1)[0]
assert "showDegraded()" not in carrier_branch
carrier_start = UI.rfind('pingFailureStreak = 0', 0, UI.find('"Health probe failed but it left over the carrier link'))
assert carrier_start >= 0
assert 'not counting against the session' in carrier_branch
# The version is tag-driven (CI rewrites these lines from the v3.x.y tag), so
# the contract pins the SHAPE, not a stale literal that broke on every bump.
version_code = re.search(r'versionCode = (\d+)', BUILD)
version_name = re.search(r'versionName = "([^"]+)"', BUILD)
assert version_code and version_name
print(f"PASS carrier-side probe failures do not set DEGRADED; version {version_name.group(1)} ({version_code.group(1)})")
