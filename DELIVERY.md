# سند تحویل پروژه BEE-VPN

این سند برای تیمی نوشته شده که می‌خواهد **BEE-VPN** را منتشر و نگهداری کند. کل سورس، ریبرند کامل‌شدهٔ نسخهٔ `3.2.0` پروژهٔ **BEEHIVE-GUARD** (اثر CFG.IR) است و آمادهٔ push به گیت‌هاب و بیلد خودکار.

---

## ۱. چه چیزی تحویل داده شد

| بخش | وضعیت |
|---|---|
| شناسه پکیج (applicationId) | `app.beevpn` — برنامه کنار نسخهٔ اصلی قابل نصب است |
| نام نمایشی و اعلان‌ها | `BEE-VPN` |
| تم رنگی | زنبوری: کهربایی `#FFC107` روی بوم تیره `#04070B` (تم روشن: عسلی تیره `#8A5D00` با تأیید کنتراست WCAG ≥ 4.5:1) |
| آیکون‌ها | لوگوی زنبور تولیدشده با AI — mipmap همهٔ تراکم‌ها (mdpi تا xxxhdpi)، لایه‌های adaptive (`beevpn_icon_fg/bg`)، splash، گلیف مونوکروم اندروید ۱۳+ و آیکون نوتیفیکیشن (وکتور زنبور) |
| زبان‌ها | فارسی + انگلیسی (منابع چینی و روسی حذف شد) |
| صفحهٔ حمایت مالی نویسندهٔ اصلی | به‌طور کامل حذف شد (UI + آدرس‌های کریپتو + ردیف تلگرام) |
| صفحهٔ درباره | بخش ABOUT تنظیمات با نام BEE-VPN، نسخه و خط انتساب «بر پایهٔ BEEHIVE-GUARD — AGPL-3.0» |
| CI/CD | ورک‌فلو `.github/workflows/build.yml` با هر push روی `master` برای سه ABI (arm64-v8a، armeabi-v7a، x86_64) + Universal، APK امضاشده می‌سازد |
| لایسنس | AGPL-3.0 حفظ شده + انتساب BEEHIVE-GUARD (اثر CFG.IR) در README و داخل اپ |

## ۲. چیزهایی که باید خودتان جایگزین کنید (Placeholder ها)

در کل سورس، نام کاربری گیت‌هاب شما به‌صورت نمادین `CFGIR200` گذاشته شده. کافی است بعد از ساخت ریپازیتوری، `CFGIR200` را با نام کاربری واقعی جایگزین کنید:

```bash
# بعد از push، در ریشهٔ پروژه:
grep -rl "CFGIR200" . --exclude-dir=.git | xargs sed -i 's/CFGIR200/YOUR-GITHUB-USER/g'
git commit -am "Set final repo owner"
```

محل‌هایی که `CFGIR200` دارند:

- `app/src/main/java/com/msnguard/vpn/AppUpdater.kt` — بررسی به‌روزرسانی از GitHub Releases
- `app/src/main/java/com/msnguard/vpn/RemotePolicy.kt` — دریافت `remote/policy.json` از شاخهٔ master
- `app/src/main/java/com/msnguard/vpn/SmartSplitSub.kt` — دریافت `remote/smart-split.json`
- `app/src/main/java/com/msnguard/vpn/MainActivity.kt` — ردیف «Source on GitHub»
- `README.md`، `README.en.md` — بج‌ها و لینک‌ها
- `ci-*.sh` — اسکریپت‌های کمکی CI

> **نکتهٔ مهم:** فایل `remote/policy.json` در همین ریپازیتوری است؛ تا وقتی فورک push نشده، اپ‌های نصب‌شده policy را از همان آدرس بالادست می‌گیرند. بعد از push به master خودتان، مسیرها به فورک اشاره می‌کنند.

## ۳. راه‌اندازی بیلد در GitHub Actions (قدم‌به‌قدم)

۱. ریپازیتوری جدید (مثلاً `YOUR-USER/BEE-VPN`) بسازید و این سورس را push کنید:

```bash
git init
git add -A
git commit -m "BEE-VPN 1.0.0 - fork of BEEHIVE-GUARD 3.2.0 (AGPL-3.0)"
git branch -M master
git remote add origin https://github.com/YOUR-USER/BEE-VPN.git
git push -u origin master
```

۲. یک keystore برای امضا بسازید (فقط یک بار، نگه‌داریش کنید — گم‌شدنش یعنی عدم امکان آپدیت):

```bash
keytool -genkeypair -v -keystore release.keystore -alias beevpn \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12
```

۳. در `Settings ▸ Secrets and variables ▸ Actions` این چهار Secret را ثبت کنید:

| Secret | مقدار |
|---|---|
| `AETHERY_KEYSTORE_BASE64` | خروجی `base64 -w0 release.keystore` |
| `AETHERY_KEYSTORE_PASSWORD` | رمز keystore |
| `AETHERY_KEY_ALIAS` | `beevpn` |
| `AETHERY_KEY_PASSWORD` | رمز کلید |

۴. (اختیاری) `BEE_IDENTITY_RELAY_HMAC` را برای فعال‌شدن مسیر Relay ثبت کنید — توضیح در بخش ۵.

۵. ورک‌فلو با push بعدی اجرا می‌شود؛ APKها در Artifacts با نام `apks` و `arm64-v8a-apk` قرار می‌گیرند.

## ۴. بیلد محلی

پیش‌نیازها: JDK 21، Android SDK با **NDK 26.3.11579264** و **CMake 3.22.1**، Rust با هدف‌های `aarch64-linux-android`، `armv7-linux-androideabi`، `x86_64-linux-android` و `cargo-ndk`.

```bash
./gradlew --no-parallel --max-workers=1 assembleRelease -PaetheryKeystore=release.keystore
```

خروجی: `app/build/outputs/apk/release/*.apk`

## ۵. وابستگی زیرساختی: Identity Relay

دو مسیر برای گرفتن هویت WARP وجود دارد و فقط یکی از آن‌ها به زیرساخت نویسندهٔ اصلی وصل است:

- **مسیر مستقیم** (پیش‌فرض و سالم): ثبت‌نام مستقیم روی `api.cloudflareclient.com` در `IdentityProvisioner.kt` — بدون هیچ وابستگی به نویسندهٔ اصلی کار می‌کند.
- **مسیر Relay** (`IdentityRelayClient.kt`): از دو Cloudflare Worker استفاده می‌کند که متعلق به پروژهٔ بالادست‌اند و کلید HMAC آن‌ها عمومی نیست؛ پس در بیلد شما این مسیر عملاً غیرفعال است و **بدون خطا بیلد می‌شود** (در CI فقط هشدار می‌دهد).

اگر خواستید Relay مخصوص خودتان را فعال کنید: یک Worker با همان قرارداد (هدرهای `X-BEE-Timestamp/Nonce/Signature` و HMAC-SHA256 روی `method\npath\ntimestamp\nnonce\nbody`) مستقر کنید، دو ثابت `RELAY_A`/`RELAY_B` را در `IdentityRelayClient.kt` به دامنهٔ خودتان تغییر دهید و HMAC را در Secret بگذارید. تا آن زمان، همان مسیر مستقیم کافی است.

## ۶. چرا نام‌های داخلی Kotlin تغییر نکردند؟

فضای نام کد (`com.msnguard.vpn`) و نام کلاس‌هایی مثل `MsnGuardVpnService` عمداً حفظ شده‌اند، چون:

- نمادهای JNI داخل `libaether.so` قفل شده‌اند؛ تغییر پکیج بدون بازسازی تمام کتابخانه‌های بومی (Rust + NDK) باعث کرش در لحظهٔ اتصال می‌شود.
- این نام‌ها هیچ‌کجا برای کاربر دیده نمی‌شوند (نه در UI، نه در تنظیمات اندروید — شناسهٔ نصب همان `app.beevpn` است).

اگر بعداً خواستید داخلی هم تمیز شود: هستهٔ Rust را از `core/` برای هر سه ABI بازسازی کنید، بعد پکیج و کلاس‌ها را با یک rename کامل جابه‌جا کنید. برای انتشار عمومی الزامی نیست.

## ۷. تعهدات مجوز (AGPL-3.0)

- فایل `LICENSE` را حذف یا تغییر ندهید.
- خط انتساب «بر پایهٔ BEEHIVE-GUARD» (اثر CFG.IR) در README (فارسی/انگلیسی) و در صفحهٔ ABOUT اپ باقی بماند.
- اگر نسخهٔ تغییر یافته را توزیع کردید، سورس کامل همان نسخه باید در دسترس باشد (بخش Releases + ریپازیتوری عمومی این شرط را برآورده می‌کند).
- اجزای وندورشده (quiche، BadVPN، lwIP، Psiphon، Tor، lyrebird) مجوز مستقل خودشان را دارند.

## ۸. وضعیت تست‌های قراردادی (tests/)

این پوشه شامل تست‌های قراردادی پایتونیِ نویسندهٔ بالادست است که فایل‌های سورس را از نظر داشتن قطعه‌کدهای مشخص می‌سنجند (CI آن‌ها را اجرا نمی‌کند):

- `test_degraded_probe_contract.py`، `test_warp_ui_contract.py`، `test_identity_relay_contract.py` و بقیه — **پاس** می‌شوند (دو مورد اول برای نسخهٔ 3.2.0 همگام‌سازی شدند).
- `test_masque_exit_node_contract.py` — ۳ ادعای آن به پیاده‌سازی قدیمی خروج MASQUE اشاره می‌کنند (مثل `probeTcpThroughExit` و `AETHER_API_ECH`) که دیگر در کد وجود ندارند؛ این بدهی تست **از قبل در بالادست** وجود داشت و به ریبرند ربطی ندارد. اگر خواستید اصلاحش کنید، باید قرارداد جدید خروج MASQUE را بر اساس کد فعلی `MsnGuardVpnService.kt` بازنویسی کنید.
- `test_identity_vault_cards.py` — برای اجرا به `javac` (JDK) نیاز دارد.

## ۹. چک‌لیست پیش از انتشار اولیه

- [ ] جایگزینی `CFGIR200` با نام کاربری واقعی
- [ ] ساخت ریپازیتوری و push
- [ ] ثبت چهار Secret امضا (+ اختیاری HMAC)
- [ ] اجرای ورک‌فلو و نصب تستی `app-arm64-v8a-release.apk`
- [ ] جایگزینی `docs/beevpn-dashboard.jpg` با اسکرین‌شات واقعی از بیلد جدید (تصویر فعلی مال نسخهٔ بالادست است)
- [ ] بررسی عنوان و توضیحات Releases

## خروجی APK محلی (ساخته‌شده در سندباکس)

چهار فایل APK نسخهٔ 3.2.0 (versionCode 449؛ نسخه‌های بعدی از CI با versionCode بالاتر می‌آیند) جدا از CI و به‌صورت محلی بیلد و با keystore تحویلی امضا شده‌اند:
`BEE-VPN-3.2.0-arm64-v8a.apk` (گوشی‌های جدید) ، `BEE-VPN-3.2.0-armeabi-v7a.apk` (گوشی‌های قدیمی ۳۲بیتی) ،
`BEE-VPN-3.2.0-x86_64-emulator.apk` (شبیه‌ساز) و `BEE-VPN-3.2.0-universal.apk` (همهٔ معماری‌ها) + `SHA256SUMS.txt`.

### keystore امضا
- فایل `beevpn-release.jks` و رمزهایش در `keystore.env` کنار همین فایل تحویل شده‌اند. کلید alias آن `beevpn` است.
- این همان کلیدی است که APKهای بالا با آن امضا شده‌اند؛ برای اینکه به‌روزرسانی‌های CI روی همین نصب‌ها آپدیت شوند،
  **باید همین keystore** را به‌صورت Base64 در Secret های گیت‌هاب بگذارید (همان مراحل بخش بالا).
- این کلید در محیط ساخت تولید شده؛ اگر ترجیح می‌دهید کلید شخصی بسازید، APKهای آینده با امضای متفاوت
  روی نسخهٔ فعلی نصب نخواهند شد (باید برنامه را حذف و نصب مجدد کنید).

### تغییر فنی بیلد (مستندسازی انحراف از آپ‌استریم)
- `core/aether/Cargo.lock` کامیت شد و `build-android.sh` دیگر آن را حذف/بازتولید نمی‌کند.
  دلیل: نسخهٔ 5.4.0 کتابخانهٔ `boring-sys` (که با generate-lockfile تازه پیدا می‌شد) یک رگرسیون bindgen روی
  armv7 دارد (`unresolved import _bindgen_ty_1`) و بیلد ۳۲بیتی را می‌شکند. قفل روی 5.2.0 بیلد را بازتولیدپذیر می‌کند.
- فایل‌های `libaether.so` عمداً در مخزن کامیت نشده‌اند (مطابق روال آپ‌استریم) — CI خودشان را می‌سازد.

## ۱۰. انتشار فایل‌های دانلود روی گیت‌هاب (Releases)

فایل‌های دانلود APK از طریق **GitHub Releases** منتشر می‌شوند — همان روش پروژهٔ بالادست
(BEEHIVE-GUARD اثر CFG.IR در تگ v3.2.0 چهار APK + `SHA256SUMS.txt` را به‌صورت Release asset منتشر می‌کند).
داخل خود ریپازیتوری نمی‌توان فایل بالای ۱۰۰ مگابایت را commit کرد؛ asset های Release تا ۲ گیگابایت پشتیبانی می‌شوند.

ابزارهای اضافه‌شده به سورس:

| فایل | نقش |
|---|---|
| `.github/workflows/release.yml` | با هر push تگ `v*`، چهار APK امضاشده می‌سازد، به نام `BEE-VPN-<نسخه>-<abi>.apk` تغییر نام می‌دهد، `SHA256SUMS.txt` می‌سازد و همه را روی یک Release جدید آپلود می‌کند — کاملاً خودکار، مثل آپ‌استریم |
| `beevpn_github.sh` | انتشار یک‌مرحله‌ای از روی ماشین محلی: ساخت ریپو (در صورت نبود) ← push سورس ← push تگ ← ساخت Release ← آپلود ۴ APK بیلدشده + SHA256SUMS. اجرا: `GH_TOKEN=ghp_xxx ./beevpn_github.sh [source-dir] [apk-dir]` |
| `RELEASE-GUIDE-FA.txt` | راهنمای گام‌به‌گام فارسی هر سه مسیر (توکن به دستیار / اسکریپت / فقط وب) + ثبت Secrets + نسخه‌های بعدی |

ترتیب پیشنهادی انتشار اولیه:

1. ثبت ۵ Secret (چهار مورد امضا + اختیاری `BEE_IDENTITY_RELAY_HMAC`) طبق بخش ۶.
2. اجرای `beevpn_github.sh` — چون ۴ APK از قبل محلی بیلد و امضا شده‌اند، Release بلافاصله
   کامل می‌شود؛ در پس‌زمینه هم اکشن `release.yml` با تگ `v3.2.0` یک بیلد CI تازه می‌سازد و
   asset های همان Release را با خروجی CI جایگزین می‌کند (`--clobber`).
3. برای نسخه‌های بعدی فقط: `git tag vX.Y.Z && git push origin vX.Y.Z`.

نکته: `AppUpdater` داخل برنامه به `releases/latest` همین ریپو نگاه می‌کند؛ با ساخت اولین
Release، چک‌کردن به‌روزرسانی داخل اپ هم فعال می‌شود.
