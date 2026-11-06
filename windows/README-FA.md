# BEE-VPN for Windows — راهنما

نسخهٔ ویندوزِ اپ BEE-VPN: **همان پروتکل‌ها، همان سرور لایسنس، همان ظاهر عسلی** — این بار روی دسکتاپ. کدها از نسخهٔ اندروید نمونه‌برداری و ۱:۱ پورت شده‌اند (BeeCrypto، LicenseManager، ShardOutboundBuilder، WarpProvisioner، الگوی پورت‌ها).

> دو کلاینت ویندوز در این پوشه هستند:
> - **کلاینت اصلی (این پوشه، Kotlin/Compose Desktop)** — تونل کامل (TUN/Wintun)، تمام پروتکل‌های اندروید، ظاهر عسلی جدید. ساخت با `.github/workflows/windows.yml`.
> - **کلاینت سبک قدیمی (`BeeVpn.Windows/`، WinForms)** — حالت پراکسی سیستمی (WARP/MASQUE) بدون SHARD و بدون تونل کامل؛ راه‌اندازِ سریع برای شبکه‌های خفیف. راهنما: `README-WINDOWS-FA.txt`.

## چه چیزی در نسخهٔ ۱ هست

| بخش | وضعیت |
| --- | --- |
| گیت لایسنس (کد BEE-XXXX-XXXX-XXXX یا حساب کاربری) | ✅ مثل اندروید — Ed25519 با java.security (بدون کتابخانهٔ خارجی) |
| پروفایل اشتراک (انقضا، روزهای باقی‌مانده، جلالی، پلن، دستگاه) | ✅ مثل اندروید/iOS |
| SHARD (استخر اشتراک Free-Configs) | ✅ همان URL، همان ETag و همان ۶ ساعت |
| VLESS · VMESS · TROJAN · Shadowsocks · Hysteria2 · SOCKS | ✅ — همان JSON خروجی که اندروید برای xray می‌سازد |
| REALITY · TLS · ECH · FinalMask · XHTTP · gRPC · WS · HTTPUpgrade | ✅ (همان streamSettings اندروید) |
| WARP | ✅ ثبت‌نام X25519 روی api.cloudflareclient.com + خروجی wireguard xray؛ fallback از مسیر SHARD |
| AnyTLS | ✅ سایدکار sing-box روی ۱۲۷.۰.۰.۱:۱۸۲۶ (همان معماری سایدکار اندروید) |
| تونل کامل شبکه | ✅ TUN بومی ویندوز (Wintun) داخل خود xray + مسیردهی 0.0.0.0/0 |
| Smart Split (فرگمنت TLS) | ✅ toggle در پیشرفته — freedom + fragment مثل اندروید |
| ورود دستی لینک‌ها و wg-quick | ✅ تب پیشرفته |
| تور و پل‌ها (obfs4/meek) | ❌ مثل iOS — در نسخهٔ دسکتاپ فعلاً نیست |

هر کد اشتراک فقط روی یک دستگاه بسته می‌شود — کد اندرویدی را روی ویندوز فعال نکنی، از گوشی خارج می‌شود (انتقال = ریست از پشتیبانی).

## معماری در یک نگاه

```
windows/
├─ src/main/kotlin/com/beevpn/win/
│  ├─ license/      ← BeeCrypto (Ed25519) + LicenseAPI + LicenseManager  [پورت اندروید]
│  ├─ shard/        ← ShardProfile/ShardFmt/ShardOutboundBuilder + Subscription  [پورت اندروید]
│  ├─ warp/         ← WarpProvisioner (ثبت‌نام WARP + wg-quick)  [پورت iOS/اندروید]
│  ├─ engine/       ← EngineConfig (xray: TUN+SOCKS 1819+خروجی) + سایدکار AnyTLS 1826
│  ├─ tunnel/       ← TunnelController — حالت‌ها و orchestration
│  └─ ui/           ← تم عسلی، فونت Vazirmatn، RTL، ارقام فارسی، جلالی، اورب
├─ BEE-VPN.bat      ← لانچر portable با ارتقای خودکار UAC
└─ resources/       ← آیکون + فونت (OFL)
```

- **پورت‌ها** (مثل نقشهٔ CoreConfig اندروید): 1819 = SOCKS محلی، 1826 = سایدکار AnyTLS.
- **موتورها**: xray.exe (پروتکل‌ها + TUN) و sing-box.exe (فقط AnyTLS) + wintun.dll — با sha256 پین شده و **داخل خود بسته** جاساز می‌شوند (resources اپ) و در اولین اتصال به `%APPDATA%\BEE-VPN\cores` استخراج می‌شوند؛ دانلود جدا لازم نیست.

## ساخت — بدون ویندوز، با GitHub Actions

وورک‌فلو `.github/workflows/windows.yml` روی رانر ویندوز خودش MSI و ZIP قابل حمل می‌سازد:

1. سیکرت‌ها مثل اندروید/iOS: `BEE_LICENSE_URL`، `BEE_LICENSE_PUBKEY_B64` (+ اختیاری `BEE_TELEGRAM_URL`، `BEE_SUPPORT_URL`).
2. تب **Actions** → **Windows build** → **Run workflow** (یا خودکار با push روی `windows/**`).
3. آرتیفکت‌ها:
   - `BEE-VPN-1.0.1-msi-x64` → نصب‌کنندهٔ ویندوز (منو + شورتکات)
   - `BEE-VPN-1.0.1-portable-x64` → پوشهٔ قابل حمل با `BEE-VPN.bat`

موتورها قبل از بسته‌بندی **پین و با sha256 راستی‌آزمایی** می‌شوند (xray v26.10.21، sing-box v1.14.4) و داخل jar اپ جاساز می‌شوند (پوشهٔ `cores-bin/cores/` در CI) — checksum ها داخل وورک‌فلو ثابت‌اند.

## ساخت محلی (روی ویندوز با JDK 21)

```bat
set BEE_LICENSE_URL=...
set BEE_LICENSE_PUBKEY_B64=...
.\gradlew.bat packageMsi
```

بدون سیکرت‌ها گیت به‌صورت عبوری کامپایل می‌شود (همان قاعدهٔ اندروید/iOS).

## اجرا

- نصب از MSI: از منوی استارت باز کن — برای «اتصال» دکمهٔ ارتقای دسترسی مدیر در تب «پیشرفته» هست.
- ZIP قابل حمل: `BEE-VPN.bat` را اجرا کن (خودش UAC می‌گیرد).
- تونل به دسترسی مدیر نیاز دارد (Wintun + مسیردهی کامل) — مثل هر کلاینت VPN دسکتاپی دیگر.

## حریم خصوصی

اپ فقط کد اشتراک و شناسهٔ دستگاه را به سرور لایسنس می‌فرستد؛ ترافیک کاربر مستقیم داخل تونل می‌رود. گزارش‌ها در `%APPDATA%\BEE-VPN\logs\engine.log` ذخیره و خطاهای حساس در همان سطح اندروید پالایش می‌شوند.

## مجوزهای بسته‌های داخل

| قطعه | مجوز |
| --- | --- |
| Xray-core | GPLv3 |
| sing-box | MIT |
| Wintun | MIT |
| Vazirmatn | SIL OFL 1.1 |
