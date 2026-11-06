// BEE-VPN for Windows — on-disk license state, DPAPI-encrypted per user.
// Same role as SecureStore + SharedPreferences of the Android app.
using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace BeeVpn;

public sealed class TokenStore
{
    public string InstallId = "";
    public string Token = "";
    public string Subject = "";
    public string Kind = "";

    static string Dir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "BEE-VPN");
    static string FilePath => Path.Combine(Dir, "license.bin");

    public static TokenStore Load()
    {
        try
        {
            byte[] enc = File.ReadAllBytes(FilePath);
            byte[] plain = ProtectedData.Unprotect(enc, null, DataProtectionScope.CurrentUser);
            return JsonSerializer.Deserialize<TokenStore>(Encoding.UTF8.GetString(plain)) ?? new TokenStore();
        }
        catch
        {
            return new TokenStore();
        }
    }

    public void Save()
    {
        Directory.CreateDirectory(Dir);
        byte[] plain = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(this));
        File.WriteAllBytes(FilePath, ProtectedData.Protect(plain, null, DataProtectionScope.CurrentUser));
    }

    public static void Clear()
    {
        try { File.Delete(FilePath); } catch { /* first run */ }
    }

    public static string CoreDir => Path.Combine(Dir, "core");
}
