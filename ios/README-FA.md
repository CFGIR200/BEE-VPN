# BEE-VPN iOS — راهنمای ساخت و نصب

نسخهٔ iOS اپ BEE-VPN: همان اشتراک، همان سرور لایسنس، همان ظاهر عسلی — این بار بومی SwiftUI.

## چه چیزی در نسخهٔ ۱ هست

| بخش | وضعیت |
| --- | --- |
| گیت لایسنس (کد فعال‌سازی BEE-XXXX-XXXX-XXXX یا حساب کاربری) | ✅ مثل اندروید — Ed25519 با CryptoKit |
| تونل WARP (WireGuard) | ✅ ثبت‌نام خودکار + WireGuardKit |
| صفحهٔ اشتراک (تاریخ انقضا، روزهای باقی‌مانده، جلالی، پلن، دستگاه) | ✅ مثل اندروید |
| تنظیم دستی wg-quick برای اپراتورهای سخت‌گیر | ✅ تب «پیشرفته» |
| هویت جدید (Regenerate) | ✅ تب «پیشرفته» |
| دانلود هویت از Relay Worker / SHARD / تور / سایفون | ❌ مخصوص اندروید — در iOS از تنظیم دستی استفاده کن |

سرور لایسنس و ربات تلگرام هیچ تغییری نمی‌خواهند؛ iOS دقیقاً همان `/activate` `/login` `/verify` را صدا می‌زند و کد یک اشتراکِ واحد بین دو پلتفرم است (هر کد فقط روی یک دستگاه بسته می‌شود — پس کد اندرویدی را روی iOS فعال نکن).

## ساخت — دو راه

### راه ۱: بدون مک، با GitHub Actions (پیشنهادی)

وورک‌فلو `.github/workflows/ios.yml` خودش روی رانر macOS اپل، IPA آمادهٔ نصب می‌سازد:

1. سیکرت‌های ریپازیتوری را مثل اندروید ست کن (اگر اندروید را می‌سازی، همین‌ها از قبل هستند):
   - `BEE_LICENSE_URL` = `https://beevpn-license.<حساب-تو>.workers.dev`
   - `BEE_LICENSE_PUBKEY_B64` = کلید عمومی سرور لایسنس
   - `BEE_TELEGRAM_URL` و `BEE_SUPPORT_URL` = لینک ربات فروش و پشتیبانی
2. تب **Actions** → **iOS build** → **Run workflow**.
3. از همان اجرا، آرتیفکت `BEE-VPN-unsigned-ipa` را دانلود کن → `BEE-VPN-unsigned.ipa`.

### راه ۲: روی مک

```bash
# ابزارها: Xcode 15+ و Go
brew install xcodegen go

# ۱) پل Go وایرگارد را بساز (فقط بار اول؛ خروجی: out/libwg-go.a)
git clone --depth 1 --branch 1.0.16-27 https://github.com/WireGuard/wireguard-apple.git ../wireguard-apple
export PLATFORM_NAME=iphoneos SDKROOT="$(xcrun --sdk iphoneos --show-sdk-path)" ARCHS="arm64"
make -C ../wireguard-apple/Sources/WireGuardKitGo -j8

# ۲) کانفیگ را تزریق و پروژه را بساز
./Scripts/gen-config.sh
xcodegen generate
open BEE-VPN.xcodeproj     # Cmd+R روی شبیه‌ساز، یا آرشیو برای دستگاه
```

## نصب IPA روی گوشی

IPA خروجی CI «امضانشده» است؛ سه راه نصب:

1. **Sideloadly / AltStore** (رایگان با اپل‌آیدی شخصی، ۷ روز اعتبار، تا ۳ اپ):
   گوشی را با کابل به کامپیوتر وصل کن، IPA را بکش داخل Sideloadly، اپل‌آیدی را بده، تمام.
2. **TrollStore / ESign** (آیفون‌های دارای نصب آفلاین): IPA را مستقیم نصب کن.
3. **TestFlight / اپ‌استور**: با اکانت Apple Developer (۹۹ دلار/سال) آرشیو را امضا کن:
   در Xcode → Signing & Capabilities → Team را انتخاب کن → Product → Archive → Distribute.
   توجه: انتشار اپ VPN در اپ‌استور بازبینی مخصوص می‌خواهد؛ TestFlight مسیر سریع‌تری است.

بعد از نصب و اولین «اتصال»، iOS اجازهٔ ساخت پروفایل VPN می‌خواهد — اجازه بده. این یک‌بار برای همیشه است.

## معماری در یک نگاه

```
ios/
├─ project.yml                      ← تعریف پروژه برای XcodeGen
├─ Scripts/gen-config.sh            ← تزریق BEE_LICENSE_URL و… (مثل BuildConfig اندروید)
├─ BEE-VPN/
│  ├─ App/BeeApp.swift              ← ورود اپ؛ گیت جلوی کنسول سه‌تبی
│  ├─ App/Theme.swift               ← پالت عسلی، رقم فارسی، تاریخ جلالی
│  ├─ License/BeeCrypto.swift       ← Ed25519 (CryptoKit) — همان توکن v1.p.sig
│  ├─ License/LicenseManager.swift  ← activate / login / verify هر ۶ ساعت + foreground
│  ├─ License/LicenseAPI.swift      ← همان سه endpoint سرور لایسنس
│  ├─ Warp/WarpProvisioner.swift    ← ثبت‌نام WARP (X25519) + ساخت wg-quick
│  ├─ VPN/TunnelController.swift    ← NETunnelProviderManager
│  └─ Views/                        ← گیت، اتصال (Orb)، اشتراک، پیشرفته
└─ BeeTunnel/
   └─ PacketTunnelProvider.swift    ← افزونهٔ تونل روی WireGuardKit
```

نکته‌های مهم:

- **اتصال کار نمی‌کند؟** پورت 2408 روی اپراتور تو بسته است — در «پیشرفته» یک کانفیگ دستی با Endpoint روی `500` یا `1701` یا `4500` بگذار (همان نردبان پورت‌های اندروید).
- **کتابخانهٔ Go:** WireGuardKitGo (نسخهٔ 1.0.16-27) باید قبل از بیلد با make ساخته شود؛ CI خودش انجام می‌دهد.
- **دستگاه دوم:** هر اشتراک فقط یک دستگاه. انتقال = ریست از پشتیبانی.
- privacy: اپ هیچ داده‌ای جز کد اشتراک و شناسهٔ دستگاه به سرور لایسنس نمی‌فرستد؛ ترافیک کاربر مستقیم داخل تونل WARP است.

<!-- ci retry -->
