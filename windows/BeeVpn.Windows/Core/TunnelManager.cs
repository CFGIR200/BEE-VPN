// BEE-VPN for Windows — tunnel manager. Runs the bundled aether.exe (the very
// engine the Android app carries as libaether.so): MASQUE over Cloudflare
// WARP, with an HTTP CONNECT proxy on 127.0.0.1:1818 and SOCKS5 on 127.0.0.1:1819.
// Connection state is detected by probing the HTTP port — engine log lines
// are surfaced to the UI for transparency, like the Android log viewer.
using System;
using System.Diagnostics;
using System.IO;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;

namespace BeeVpn;

public sealed class TunnelManager
{
    public const int HttpPort = 1818;
    public const int SocksPort = 1819;

    Process _proc;
    volatile bool _stopping;
    readonly CancellationTokenSource _probeCts = new();

    public string ScanMode { get; set; } = "turbo";      // turbo | balanced | thorough | ironclad
    public string Noize { get; set; } = "firewall";      // off | light | firewall | balanced | gfw | aggressive
    public bool Fragment { get; set; } = true;           // TLS ClientHello fragmentation (Iran-friendly)
    public bool UseH2 { get; set; }                      // HTTP/2 (TCP) fallback for QUIC-hostile networks
    public bool NoIranExit { get; set; }                 // refuse tunnels exiting from Iran

    public bool IsRunning => _proc is not null && !_proc.HasExited;

    public string ExePath => Path.Combine(AppContext.BaseDirectory, "core", "aether.exe");

    public event Action<string> Log;
    public event Action<string> StateChanged;   // starting | connected | failed | stopped

    void OnLog(string line)
    {
        try { Log?.Invoke(line.TrimEnd()); } catch { /* UI callbacks must not kill the tunnel */ }
    }

    public void Start()
    {
        if (IsRunning) return;
        if (!File.Exists(ExePath))
        {
            OnLog("aether.exe یافت نشد: " + ExePath);
            StateChanged?.Invoke("failed");
            return;
        }
        _stopping = false;
        Directory.CreateDirectory(TokenStore.CoreDir);

        var psi = new ProcessStartInfo
        {
            FileName = ExePath,
            WorkingDirectory = TokenStore.CoreDir,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        // Nothing interactive: every question is answered by a flag.
        var a = psi.ArgumentList;
        a.Add("--masque");
        a.Add("-4");
        if (UseH2) a.Add("--h2");
        a.Add("--scan"); a.Add(ScanMode);
        a.Add("--noize"); a.Add(Noize);
        a.Add("--bind"); a.Add($"127.0.0.1:{SocksPort}");
        a.Add("--http-proxy"); a.Add($"127.0.0.1:{HttpPort}");
        a.Add("--config"); a.Add("aether.toml");
        a.Add("--quick-reconnect");
        a.Add("--log-level"); a.Add("info");
        if (Fragment) a.Add("--fragment");
        if (NoIranExit) { a.Add("--exit-loc"); a.Add("!IR"); }

        OnLog("$ aether " + string.Join(' ', a));
        _proc = Process.Start(psi);
        if (_proc is null)
        {
            StateChanged?.Invoke("failed");
            return;
        }
        _proc.EnableRaisingEvents = true;
        _proc.Exited += (_, _) => { if (!_stopping) StateChanged?.Invoke("stopped"); };
        _proc.OutputDataReceived += (_, e) => { if (e.Data is not null) OnLog(e.Data); };
        _proc.ErrorDataReceived += (_, e) => { if (e.Data is not null) OnLog(e.Data); };
        _proc.BeginOutputReadLine();
        _proc.BeginErrorReadLine();

        StateChanged?.Invoke("starting");
        _ = WaitUntilConnectedAsync(_probeCts.Token);
    }

    async Task WaitUntilConnectedAsync(CancellationToken ct)
    {
        // The HTTP proxy port only accepts connections once the tunnel is up,
        // so a TCP probe doubles as the readiness signal (deadline: 3 min —
        // scanning + WARP registration on a first run can be slow).
        for (int i = 0; i < 180; i++)
        {
            if (ct.IsCancellationRequested) return;
            if (_proc is null || _proc.HasExited)
            {
                StateChanged?.Invoke("failed");
                return;
            }
            if (await Task.Run(() => PortOpen(HttpPort), ct))
            {
                StateChanged?.Invoke("connected");
                return;
            }
            try { await Task.Delay(1000, ct); }
            catch (OperationCanceledException) { return; }
        }
        OnLog("مهلت اتصال تمام شد (۱۸۰ ثانیه)");
        StateChanged?.Invoke("failed");
    }

    static bool PortOpen(int port)
    {
        try
        {
            using var c = new TcpClient();
            var r = c.BeginConnect("127.0.0.1", port, null, null);
            if (!r.AsyncWaitHandle.WaitOne(700)) return false;
            c.EndConnect(r);
            return true;
        }
        catch
        {
            return false;
        }
    }

    public void Stop()
    {
        _stopping = true;
        try { _probeCts.Cancel(); } catch { /* already gone */ }
        if (_proc is not null && !_proc.HasExited)
        {
            try
            {
                _proc.Kill(entireProcessTree: true);
                _proc.WaitForExit(5000);
            }
            catch { /* already exiting */ }
        }
        _proc = null;
        StateChanged?.Invoke("stopped");
    }
}
