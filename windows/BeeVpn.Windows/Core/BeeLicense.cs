// BEE-VPN for Windows — license client. Mirrors LicenseManager.kt of the
// Android app exactly: one entitlement token `v1.<payloadB64url>.<sigB64url>`
// signed with Ed25519 by the Cloudflare license Worker, payload JSON
// {s,k,p,i,e,t} (subject, kind, plan, install id, expiry epoch sec, issued at).
// Local verification is signature + device match + clock; the server is the
// only place a token can be born, so all real trust lives there.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Crypto.Signers;

namespace BeeVpn;

public sealed class BeeLicense
{
    // The operator's real endpoints (see license/wrangler.toml + LICENSE-SETUP-FA.txt).
    public const string DefaultBaseUrl = "https://beevpn-license.java-aqua-shrivel.workers.dev";
    // Public by design — the same key ships in the Android BuildConfig and is
    // served by GET /pubkey. A static field (not const) so core tests can
    // swap in their own keypair.
    public static string PubKeyB64Url = "S-H-4JCuQ3c_Asf4JDJTHoWLePvEQV14Bxcy7N2WORU";
    public const string UserAgent = "BEE-VPN-Windows";

    /// <summary>Overridable for tests; the app never touches this.</summary>
    public static string BaseUrl = DefaultBaseUrl;

    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(20) };

    public string InstallId = "";
    public string Token = "";
    public string Subject = "";
    public string Kind = "";     // "code" | "account"
    public string Plan = "";
    public long Expiry;          // epoch sec, 0 when unknown
    public long? ActivatedAt;    // epoch sec (from /verify, may be null)
    public string LastError = ""; // localized Persian error

    static readonly Dictionary<string, string> FaErrors = new()
    {
        ["unknown code"] = "کد فعال‌سازی نامعتبر است",
        ["bound to another device"] = "این کد روی دستگاه دیگری فعال شده است",
        ["expired"] = "اشتراک منقضی شده — برای ادامه، تمدید کنید",
        ["revoked"] = "این اشتراک باطل شده است",
        ["wrong username or password"] = "نام کاربری یا رمز عبور اشتباه است",
        ["too many attempts"] = "تلاش‌های زیاد — چند دقیقه بعد دوباره امتحان کنید",
        ["invalid code format"] = "قالب کد درست نیست (BEE-XXXX-XXXX-XXXX)",
        ["device mismatch"] = "این توکن به دستگاه دیگری تعلق دارد",
    };

    static string FaError(string serverError)
    {
        if (string.IsNullOrEmpty(serverError)) return "ارتباط با سرور لایسنس برقرار نشد — دوباره تلاش کنید";
        foreach (var kv in FaErrors)
            if (serverError.Contains(kv.Key, StringComparison.OrdinalIgnoreCase))
                return kv.Value;
        return "ارتباط با سرور لایسنس برقرار نشد — دوباره تلاش کنید";
    }

    // ------------------------------------------------------------ install id

    /// <summary>32 hex chars, generated once and kept beside the token.</summary>
    public void EnsureInstallId(Func<string> loadExisting, Action<string> persistNew)
    {
        if (string.IsNullOrWhiteSpace(InstallId))
            InstallId = loadExisting() ?? "";
        if (string.IsNullOrWhiteSpace(InstallId))
        {
            var bytes = RandomNumberGenerator.GetBytes(16);
            InstallId = Convert.ToHexString(bytes).ToLowerInvariant();
            persistNew(InstallId);
        }
    }

    // ------------------------------------------------------- token plumbing

    static byte[] B64UrlDecode(string s)
    {
        s = s.Replace('-', '+').Replace('_', '/');
        int pad = (4 - s.Length % 4) % 4;
        if (pad > 0) s += new string('=', pad);
        return Convert.FromBase64String(s);
    }

    static string B64UrlEncode(byte[] b)
        => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public sealed class TokenPayload
    {
        public string Subject; public string Kind; public string Plan;
        public string InstallId; public long Expiry; public long IssuedAt;
    }

    /// <summary>Ed25519 over the raw payload bytes — the same BouncyCastle
    /// Ed25519Signer path LicenseManager.kt runs on Android. False means "not
    /// entitled" whether the signature is bad or the token is garbled.</summary>
    public static bool TryVerifyToken(string token, byte[] pubRaw, out TokenPayload payload)
    {
        payload = null;
        try
        {
            var parts = token.Split('.');
            if (parts.Length != 3 || parts[0] != "v1") return false;
            byte[] payloadBytes = B64UrlDecode(parts[1]);
            byte[] sig = B64UrlDecode(parts[2]);
            var verifier = new Ed25519Signer();
            verifier.Init(false, new Ed25519PublicKeyParameters(pubRaw, 0));
            verifier.BlockUpdate(payloadBytes, 0, payloadBytes.Length);
            if (!verifier.VerifySignature(sig)) return false;
            using var doc = JsonDocument.Parse(Encoding.UTF8.GetString(payloadBytes));
            var root = doc.RootElement;
            payload = new TokenPayload
            {
                Subject = root.TryGetProperty("s", out var s) ? s.GetString() : "",
                Kind = root.TryGetProperty("k", out var k) ? k.GetString() : "code",
                Plan = root.TryGetProperty("p", out var p) ? p.GetString() : "",
                InstallId = root.TryGetProperty("i", out var i) ? i.GetString() : "",
                Expiry = root.TryGetProperty("e", out var e) && e.TryGetInt64(out var ee) ? ee : 0,
                IssuedAt = root.TryGetProperty("t", out var t) && t.TryGetInt64(out var tt) ? tt : 0,
            };
            return true;
        }
        catch
        {
            return false;
        }
    }

    static byte[] PublicKeyBytes() => B64UrlDecode(PubKeyB64Url);

    /// <summary>Full local check: token parses, signature ok, device matches, not expired.</summary>
    public bool Entitled()
    {
        if (string.IsNullOrWhiteSpace(Token)) { LastError = ""; return false; }
        if (!TryVerifyToken(Token, PublicKeyBytes(), out var p))
        {
            LastError = "توکن نامعتبر — دوباره وارد شوید";
            return false;
        }
        if (p.InstallId != InstallId)
        {
            LastError = "این اشتراک به دستگاه دیگری تعلق دارد";
            return false;
        }
        if (p.Expiry <= DateTimeOffset.UtcNow.ToUnixTimeSeconds())
        {
            Subject = p.Subject; Kind = p.Kind; Plan = p.Plan; Expiry = p.Expiry;
            LastError = "اشتراک منقضی شده — برای ادامه، تمدید کنید";
            return false;
        }
        Subject = p.Subject; Kind = p.Kind; Plan = p.Plan; Expiry = p.Expiry;
        LastError = "";
        return true;
    }

    public int DaysRemaining()
    {
        if (Expiry <= 0) return 0;
        long secs = Expiry - DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        return secs <= 0 ? 0 : (int)(secs / 86400) + 1;
    }

    // ------------------------------------------------------------- HTTP API

    async Task<JsonElement> PostAsync(string path, object body)
    {
        var json = JsonSerializer.Serialize(body);
        using var content = new StringContent(json, Encoding.UTF8, "application/json");
        using var req = new HttpRequestMessage(HttpMethod.Post, BaseUrl.TrimEnd('/') + path) { Content = content };
        req.Headers.UserAgent.ParseAdd(UserAgent);
        using var resp = await Http.SendAsync(req);
        string text = await resp.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(text) ? "{}" : text);
        var root = doc.RootElement.Clone();
        LastError = FaError(root.TryGetProperty("error", out var e) ? e.GetString() : "");
        return (int)resp.StatusCode >= 200 && (int)resp.StatusCode < 300 && root.TryGetProperty("ok", out var ok) && ok.ValueKind == JsonValueKind.True
            ? root
            : throw new BeeApiException(LastError);
    }

    public sealed class BeeApiException : Exception
    {
        public BeeApiException(string msg) : base(msg) { }
    }

    /// <summary>Activation-code path — mirrors /activate of the license Worker.</summary>
    public async Task<bool> ActivateWithCodeAsync(string code, string model)
    {
        try
        {
            var root = await PostAsync("/activate", new Dictionary<string, object>
            {
                ["code"] = (code ?? "").Trim().ToUpperInvariant(),
                ["install_id"] = InstallId,
                ["model"] = model ?? "Windows",
            });
            Token = root.GetProperty("token").GetString() ?? "";
            Subject = root.TryGetProperty("subject", out var s) ? s.GetString() : "";
            Kind = root.TryGetProperty("kind", out var k) ? k.GetString() : "code";
            if (root.TryGetProperty("expiry", out var e) && e.TryGetInt64(out var ex)) Expiry = ex;
            LastError = "";
            Entitled();
            return true;
        }
        catch (BeeApiException ex)
        {
            LastError = ex.Message;
            return false;
        }
        catch
        {
            LastError = "ارتباط با سرور لایسنس برقرار نشد — دوباره تلاش کنید";
            return false;
        }
    }

    /// <summary>Username/password path — mirrors /login (accounts issued via /adduser).</summary>
    public async Task<bool> LoginAsync(string username, string password)
    {
        try
        {
            var root = await PostAsync("/login", new Dictionary<string, object>
            {
                ["username"] = (username ?? "").Trim().ToLower(CultureInfo.InvariantCulture),
                ["password"] = password ?? "",
                ["install_id"] = InstallId,
            });
            Token = root.GetProperty("token").GetString() ?? "";
            Subject = root.TryGetProperty("subject", out var s) ? s.GetString() : "";
            Kind = root.TryGetProperty("kind", out var k) ? k.GetString() : "account";
            if (root.TryGetProperty("expiry", out var e) && e.TryGetInt64(out var ex)) Expiry = ex;
            LastError = "";
            Entitled();
            return true;
        }
        catch (BeeApiException ex)
        {
            LastError = ex.Message;
            return false;
        }
        catch
        {
            LastError = "ارتباط با سرور لایسنس برقرار نشد — دوباره تلاش کنید";
            return false;
        }
    }

    /// <summary>
    /// Fire-and-forget server check (revocation, admin extends). Returns false
    /// when the token is dead (revoked / expired / rebound) — the caller then
    /// drops the stored token and shows the gate, exactly like revalidateAsync
    /// of the Android app.
    /// </summary>
    public async Task<bool> RevalidateAsync()
    {
        if (string.IsNullOrWhiteSpace(Token)) return false;
        try
        {
            var root = await PostAsync("/verify", new Dictionary<string, object>
            {
                ["token"] = Token,
                ["install_id"] = InstallId,
            });
            var fresh = root.GetProperty("token").GetString() ?? "";
            if (fresh.Length > 0) Token = fresh;
            Subject = root.TryGetProperty("subject", out var s) ? s.GetString() : Subject;
            Kind = root.TryGetProperty("kind", out var k) ? k.GetString() : Kind;
            if (root.TryGetProperty("expiry", out var e) && e.TryGetInt64(out var ex)) Expiry = ex;
            if (root.TryGetProperty("activated_at", out var a))
            {
                if (a.ValueKind == JsonValueKind.Number && a.TryGetInt64(out var av)) ActivatedAt = av;
                else ActivatedAt = null;
            }
            return true;
        }
        catch (BeeApiException)
        {
            // 403/409 family: revoked, expired or rebound — token is dead weight.
            Token = "";
            return false;
        }
        catch
        {
            // Offline / blocked: the token's own expiry still stands.
            return true;
        }
    }

    public void SignOut()
    {
        Token = ""; Subject = ""; Kind = ""; Plan = ""; Expiry = 0; ActivatedAt = null;
    }
}
