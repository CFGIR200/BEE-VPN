// BEE-VPN for Windows — system-proxy switch (WinINET per-user settings).
// The tunnel engine (Aether) serves SOCKS5 + an HTTP CONNECT proxy on
// loopback; pointing the user's system proxy at the HTTP port gives every
// proxy-aware application (browsers, most Windows apps) the tunnel, with no
// admin rights or drivers needed. Previous settings are saved and restored.
using System;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace BeeVpn;

public static class SysProxy
{
    const string KeyPath = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";
    const int InternetOptionSettingsChanged = 39;
    const int InternetOptionRefresh = 37;

    const string ProxyAddress = "127.0.0.1:1818";
    const string BypassList =
        "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;" +
        "172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>";

    static bool _saved;
    static int _savedEnable;
    static string _savedServer = "";
    static string _savedOverride = "";

    [DllImport("wininet.dll", SetLastError = true)]
    static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength);

    static void RefreshWinINET()
    {
        InternetSetOption(IntPtr.Zero, InternetOptionSettingsChanged, IntPtr.Zero, 0);
        InternetSetOption(IntPtr.Zero, InternetOptionRefresh, IntPtr.Zero, 0);
    }

    public static void Enable()
    {
        using var key = Registry.CurrentUser.OpenSubKey(KeyPath, writable: true);
        if (key is null) return;
        if (!_saved)
        {
            _savedEnable = (int)(key.GetValue("ProxyEnable") ?? 0);
            _savedServer = (key.GetValue("ProxyServer") as string) ?? "";
            _savedOverride = (key.GetValue("ProxyOverride") as string) ?? "";
            _saved = true;
        }
        key.SetValue("ProxyEnable", 1, RegistryValueKind.DWord);
        key.SetValue("ProxyServer", ProxyAddress);
        key.SetValue("ProxyOverride", BypassList);
        RefreshWinINET();
    }

    public static void DisableAndRestore()
    {
        using var key = Registry.CurrentUser.OpenSubKey(KeyPath, writable: true);
        if (key is null) return;
        if (_saved)
        {
            key.SetValue("ProxyEnable", _savedEnable, RegistryValueKind.DWord);
            if (_savedServer.Length > 0) key.SetValue("ProxyServer", _savedServer); else key.DeleteValue("ProxyServer", false);
            if (_savedOverride.Length > 0) key.SetValue("ProxyOverride", _savedOverride); else key.DeleteValue("ProxyOverride", false);
            _saved = false;
        }
        else
        {
            key.SetValue("ProxyEnable", 0, RegistryValueKind.DWord);
        }
        RefreshWinINET();
    }
}
