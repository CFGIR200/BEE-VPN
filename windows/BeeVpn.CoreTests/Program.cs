// BEE-VPN for Windows — core logic tests (no UI): Jalali math, Ed25519 token
// format, HTTP client flows, Persian error mapping. Exit code 0 = all pass.
using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using BeeVpn;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Crypto.Signers;
using Org.BouncyCastle.Crypto.Generators;
using Org.BouncyCastle.Security;

await TestJalaliAsync();
await TestTokenAsync();
await TestApiAsync();
Console.WriteLine("\nALL CORE TESTS PASSED");

// --------------------------------------------------------------------- jalali

static void Fail(string what) { Console.WriteLine("  ❌ " + what); Environment.Exit(1); }
static void Good(string what) => Console.WriteLine("  ✅ " + what);

static async Task TestJalaliAsync()
{
    Console.WriteLine("Jalali calendar:");
    var (y1, m1, d1) = Jalali.ToJalali(new DateTime(2026, 3, 21, 12, 0, 0));
    if ((y1, m1, d1) != (1405, 1, 1)) Fail($"Nowruz 2026 → {y1}/{m1}/{d1} (باید ۱۴۰۵/۰۱/۰۱)");
    else Good("2026-03-21 → ۱۴۰۵/۰۱/۰۱");

    var (y2, m2, d2) = Jalali.ToJalali(new DateTime(2026, 11, 6, 12, 0, 0));
    if ((y2, m2, d2) != (1405, 8, 15)) Fail($"2026-11-06 → {y2}/{m2}/{d2} (باید ۱۴۰۵/۰۸/۱۵)");
    else Good("2026-11-06 → ۱۴۰۵/۰۸/۱۵");

    var (y3, m3, d3) = Jalali.ToJalali(new DateTime(2027, 3, 20, 12, 0, 0));
    if ((y3, m3, d3) != (1405, 12, 29)) Fail($"2027-03-20 → {y3}/{m3}/{d3} (1405 عادی: اسفند ۲۹)");
    else Good("2027-03-20 → ۱۴۰۵/۱۲/۲۹ (سال عادی)");

    // The day after Nowruz.
    var (y4, m4, d4) = Jalali.ToJalali(new DateTime(2026, 3, 22, 12, 0, 0));
    if ((y4, m4, d4) != (1405, 1, 2)) Fail($"2026-03-22 → {y4}/{m4}/{d4}");
    else Good("2026-03-22 → ۱۴۰۵/۰۱/۰۲");

    if (Jalali.Fa(250000) != "۲۵۰٬۰۰۰") Fail("Fa(250000) = " + Jalali.Fa(250000));
    else Good("Fa(250000) = ۲۵۰٬۰۰۰ (گروه‌بندی سه‌رقمی)");

    if (Jalali.Fa(45) != "۴۵") Fail("Fa(45)");
    else Good("Fa(45) = ۴۵");

    // Tehran (UTC+3:30) mapping: 2026-11-06 20:00 UTC = 23:30 Tehran, same day.
    string date = Jalali.FaDateEpoch(DateTimeOffset.Parse("2026-11-06T20:00:00+00:00").ToUnixTimeSeconds());
    if (!date.StartsWith("۱۴۰۵/۰۸/۱۵")) Fail("FaDateEpoch 20:00Z → " + date);
    else Good("FaDateEpoch از UTC به تهران درست شیفت می‌شود: " + date);

    // 21:31 UTC = 01:01 next day Tehran → ۱۴۰۵/۰۸/۱۶
    string date2 = Jalali.FaDateEpoch(DateTimeOffset.Parse("2026-11-06T21:31:00+00:00").ToUnixTimeSeconds());
    if (!date2.StartsWith("۱۴۰۵/۰۸/۱۶")) Fail("FaDateEpoch 21:31Z → " + date2);
    else Good("گذر از نیمه‌شب تهران: " + date2);

    await Task.CompletedTask;
}

// ---------------------------------------------------------------------- token

static string B64U(byte[] b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');

static async Task TestTokenAsync()
{
    Console.WriteLine("Ed25519 token (v1.p.sig — the Worker's wire format):");
    var gen = new Ed25519KeyPairGenerator();
    gen.Init(new Org.BouncyCastle.Crypto.Parameters.Ed25519KeyGenerationParameters(new SecureRandom()));
    var kp = gen.GenerateKeyPair();
    var priv = (Ed25519PrivateKeyParameters)kp.Private;
    byte[] rawPub = ((Ed25519PublicKeyParameters)kp.Public).GetEncoded();
    BeeLicense.PubKeyB64Url = B64U(rawPub);

    byte[] Sig(byte[] data)
    {
        var s = new Ed25519Signer();
        s.Init(true, priv);
        s.BlockUpdate(data, 0, data.Length);
        return s.GenerateSignature();
    }

    long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
    byte[] payload = JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, object>
    {
        ["s"] = "BEE-TEST-TEST-TEST",
        ["k"] = "code",
        ["p"] = "m1",
        ["i"] = "0123456789abcdef0123456789abcdef",
        ["e"] = now + 3600,
        ["t"] = now,
    });
    byte[] sig = Sig(payload);
    string token = $"v1.{B64U(payload)}.{B64U(sig)}";

    if (!BeeLicense.TryVerifyToken(token, rawPub, out var p)) Fail("توکن درست ولید نشد");
    else Good("توکن امضاشده ولید شد — s=" + p.Subject + " p=" + p.Plan);
    if (p.Subject != "BEE-TEST-TEST-TEST" || p.Expiry != now + 3600) Fail("فیلدهای payload نادرست");
    else Good("فیلدهای payload (s/k/p/i/e/t) درست خوانده شدند");

    // Tampered payload must fail.
    byte[] badPayload = JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, object>
    {
        ["s"] = "BEE-EVIL-EVIL-EVIL", ["k"] = "code", ["p"] = "m12",
        ["i"] = "0123456789abcdef0123456789abcdef", ["e"] = now + 999999, ["t"] = now,
    });
    string evil = $"v1.{B64U(badPayload)}.{B64U(sig)}";
    if (BeeLicense.TryVerifyToken(evil, rawPub, out _)) Fail("پیلود دستکاری‌شده ولید شد!");
    else Good("پیلود دستکاری‌شده رد شد");

    if (BeeLicense.TryVerifyToken("v1.abc.def", rawPub, out _)) Fail("گاربیج ولید شد");
    else Good("توکن گاربیج رد شد");

    // Entitled(): expired token.
    var lic = new BeeLicense { InstallId = "0123456789abcdef0123456789abcdef", Token = token };
    if (!lic.Entitled()) Fail("توکن معتبر Entitled نشد: " + lic.LastError);
    else Good("Entitled() با توکن معتبر و install_id درست");
    if (lic.DaysRemaining() < 1 || lic.DaysRemaining() > 2) Fail("DaysRemaining نادرست: " + lic.DaysRemaining());
    else Good("DaysRemaining() = " + lic.DaysRemaining());

    byte[] oldPayload = JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, object>
    {
        ["s"] = "BEE-TEST-TEST-TEST", ["k"] = "code", ["p"] = "m1",
        ["i"] = "0123456789abcdef0123456789abcdef", ["e"] = now - 10, ["t"] = now - 1000,
    });
    lic.Token = $"v1.{B64U(oldPayload)}.{B64U(Sig(oldPayload))}";
    if (lic.Entitled()) Fail("توکن منقضی Entitled شد!");
    else Good("توکن منقضی رد شد («" + lic.LastError + "»)");

    // Wrong device.
    byte[] otherDev = JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, object>
    {
        ["s"] = "BEE-TEST-TEST-TEST", ["k"] = "code", ["p"] = "m1",
        ["i"] = "ffffffffffffffffffffffffffffffff", ["e"] = now + 3600, ["t"] = now,
    });
    lic.Token = $"v1.{B64U(otherDev)}.{B64U(Sig(otherDev))}";
    if (lic.Entitled()) Fail("توکن دستگاه دیگر Entitled شد!");
    else Good("توکن دستگاه دیگر رد شد («" + lic.LastError + "»)");

    await Task.CompletedTask;
}

// ------------------------------------------------------------------------ api

static async Task TestApiAsync()
{
    Console.WriteLine("License API client (/activate, /verify, error mapping):");
    using var srv = new FakeLicenseServer();

    var gen = new Ed25519KeyPairGenerator();
    gen.Init(new Org.BouncyCastle.Crypto.Parameters.Ed25519KeyGenerationParameters(new SecureRandom()));
    var kp = gen.GenerateKeyPair();
    var priv = (Ed25519PrivateKeyParameters)kp.Private;
    byte[] rawPub = ((Ed25519PublicKeyParameters)kp.Public).GetEncoded();
    BeeLicense.PubKeyB64Url = B64U(rawPub);
    BeeLicense.BaseUrl = $"http://127.0.0.1:{srv.Port}";

    byte[] Sig(byte[] data)
    {
        var s = new Ed25519Signer();
        s.Init(true, priv);
        s.BlockUpdate(data, 0, data.Length);
        return s.GenerateSignature();
    }

    long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
    var lic = new BeeLicense();
    lic.EnsureInstallId(() => null, (_) => { });

    // --- /activate success
    byte[] payload = JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, object>
    {
        ["s"] = "BEE-WIN1-WIN1-WIN1", ["k"] = "code", ["p"] = "m3",
        ["i"] = lic.InstallId, ["e"] = now + 90 * 86400, ["t"] = now,
    });
    srv.Status = 200;
    srv.Body = JsonSerializer.Serialize(new Dictionary<string, object>
    {
        ["ok"] = true,
        ["token"] = $"v1.{B64U(payload)}.{B64U(Sig(payload))}",
        ["subject"] = "BEE-WIN1-WIN1-WIN1",
        ["plan"] = "m3",
        ["kind"] = "code",
        ["expiry"] = now + 90 * 86400,
    });
    if (!await lic.ActivateWithCodeAsync("bee-win1-win1-win1", "TEST-BOX")) Fail("/activate ناموفق: " + lic.LastError);
    else Good($"/activate موفق — subject={lic.Subject} plan={lic.Plan}");
    if (!srv.LastBody.Contains("BEE-WIN1-WIN1-WIN1")) Fail("کد ارسالی normalize نشده بود (باید UPPERCASE برود)");
    else Good("کد قبل از ارسال UPPERCASE شد");

    // --- /verify refresh + activated_at
    byte[] payload2 = JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, object>
    {
        ["s"] = "BEE-WIN1-WIN1-WIN1", ["k"] = "code", ["p"] = "m3",
        ["i"] = lic.InstallId, ["e"] = now + 90 * 86400, ["t"] = now,
    });
    srv.Body = JsonSerializer.Serialize(new Dictionary<string, object>
    {
        ["ok"] = true,
        ["token"] = $"v1.{B64U(payload2)}.{B64U(Sig(payload2))}",
        ["subject"] = "BEE-WIN1-WIN1-WIN1",
        ["plan"] = "m3",
        ["kind"] = "code",
        ["expiry"] = now + 90 * 86400,
        ["activated_at"] = now - 3600,
    });
    if (!await lic.RevalidateAsync()) Fail("/verify ناموفق: " + lic.LastError);
    else Good("/verify موفق — activated_at خوانده شد");
    if (lic.ActivatedAt != now - 3600) Fail("activated_at نادرست: " + lic.ActivatedAt);
    else Good("ActivatedAt = " + Jalali.FaDateTimeEpoch(lic.ActivatedAt.Value));

    // --- /verify revocation → token cleared
    srv.Status = 403;
    srv.Body = "{\"ok\":false,\"error\":\"revoked\"}";
    if (await lic.RevalidateAsync()) Fail("توکن باطل هنوز زنده بود");
    else Good("/verify revoked → توکن پاک شد (همند revalidateAsync اندروید)");

    // --- Persian error mapping on /activate
    srv.Status = 401;
    srv.Body = "{\"ok\":false,\"error\":\"unknown code\"}";
    if (await lic.ActivateWithCodeAsync("BEE-0000-0000-0000", "T")) Fail("کد ناشناس قبول شد");
    else if (lic.LastError != "کد فعال‌سازی نامعتبر است") Fail("خطای فارسی نادرست: " + lic.LastError);
    else Good("«unknown code» → «کد فعال‌سازی نامعتبر است»");

    srv.Status = 409;
    srv.Body = "{\"ok\":false,\"error\":\"bound to another device\"}";
    if (await lic.ActivateWithCodeAsync("BEE-0000-0000-0000", "T")) Fail("کد بایند‌شده قبول شد");
    else if (lic.LastError != "این کد روی دستگاه دیگری فعال شده است") Fail("خطای فارسی نادرست: " + lic.LastError);
    else Good("«bound to another device» → «این کد روی دستگاه دیگری فعال شده است»");

    // --- server down → honest network error, no crash
    srv.Dispose();
    if (await lic.ActivateWithCodeAsync("BEE-0000-0000-0000", "T")) Fail("سرور مرده قبول شد؟!");
    else Good("سرور در دسترس نیست → «" + lic.LastError + "»");

    BeeLicense.BaseUrl = BeeLicense.DefaultBaseUrl;
}

sealed class FakeLicenseServer : IDisposable
{
    public HttpListener Listener = new();
    public int Status = 200;
    public string Body = "{}";
    public string LastPath = "";
    public string LastBody = "";

    public FakeLicenseServer()
    {
        // find a free port
        var tmp = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        tmp.Start();
        int port = ((IPEndPoint)tmp.LocalEndpoint).Port;
        tmp.Stop();
        Listener.Prefixes.Add($"http://127.0.0.1:{port}/");
        Listener.Start();
        Port = port;
        _ = Task.Run(LoopAsync);
    }

    public int Port { get; }

    async Task LoopAsync()
    {
        while (Listener.IsListening)
        {
            HttpListenerContext ctx;
            try { ctx = await Listener.GetContextAsync(); }
            catch { return; }
            LastPath = ctx.Request.Url.AbsolutePath;
            using var r = new StreamReader(ctx.Request.InputStream);
            LastBody = await r.ReadToEndAsync();
            byte[] outB = Encoding.UTF8.GetBytes(Body);
            ctx.Response.StatusCode = Status;
            ctx.Response.ContentType = "application/json";
            await ctx.Response.OutputStream.WriteAsync(outB);
            ctx.Response.Close();
        }
    }

    public void Dispose() { try { Listener.Stop(); Listener.Close(); } catch { } }
}

