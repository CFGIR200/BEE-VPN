// BEE-VPN for Windows — main screen: connect / disconnect, engine settings,
// profile, and a live engine log. Right-to-left, honey theme, like the
// Android dashboard. Connect = tunnel up + system proxy on; Disconnect =
// proxy restored + tunnel killed.
using System;
using System.Drawing;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace BeeVpn;

public sealed class MainForm : Form
{
    readonly BeeLicense _lic;
    readonly TokenStore _store;
    readonly TunnelManager _tunnel = new();

    readonly Label _status = Theme.MakeLabel("قطع", Theme.H2, Theme.Muted);
    readonly Label _dot = Theme.MakeLabel("●", new Font("Segoe UI", 18f, FontStyle.Bold), Theme.Muted);
    readonly Button _toggle;
    readonly TextBox _log = new()
    {
        Multiline = true,
        ReadOnly = true,
        ScrollBars = ScrollBars.Vertical,
        BackColor = Color.FromArgb(9, 14, 20),
        ForeColor = Color.FromArgb(120, 190, 120),
        Font = Theme.Mono,
        BorderStyle = BorderStyle.FixedSingle,
    };
    readonly ComboBox _scan, _noize;
    readonly CheckBox _fragment, _h2, _noIran;
    bool _connected;

    public MainForm(BeeLicense lic, TokenStore store)
    {
        _lic = lic; _store = store;

        Text = "BEE-VPN 3.3.1 — ویندوز";
        ClientSize = new Size(560, 560);
        BackColor = Theme.Bg;
        Font = Theme.Body;
        RightToLeft = RightToLeft.Yes;
        RightToLeftLayout = true;
        StartPosition = FormStartPosition.CenterScreen;

        var brand = Theme.MakeLabel("🐝 BEE-VPN", Theme.Title, Theme.Amber);
        brand.Location = new Point(24, 12);
        Controls.Add(brand);

        var ver = Theme.MakeLabel("نسخهٔ 3.3.1 — موتور Aether (MASQUE/WARP)", Theme.Small, Theme.Muted);
        ver.Location = new Point(24, 44);
        Controls.Add(ver);

        _dot.Location = new Point(500, 14);
        _dot.Anchor = AnchorStyles.Top | AnchorStyles.Left;
        Controls.Add(_dot);

        _status.Location = new Point(430, 28);
        Controls.Add(_status);

        _toggle = Theme.HoneyButton("اتصال", 260, 56);
        _toggle.Location = new Point(150, 78);
        _toggle.Font = Theme.Big;
        _toggle.Height = 64;
        _toggle.Click += async (_, _) => await ToggleAsync();
        Controls.Add(_toggle);

        var profile = Theme.GhostButton("👤 پروفایل اشتراک", 130, 40);
        profile.Location = new Point(410, 158);
        profile.Click += (_, _) => new ProfileForm(_lic, () => { }).ShowDialog(this);
        Controls.Add(profile);

        var support = Theme.GhostButton("🆘 پشتیبانی", 100, 40);
        support.Location = new Point(296, 158);
        support.Click += (_, _) => TryOpen("https://t.me/Bee_hive_VPN");
        Controls.Add(support);

        var panel = new Panel
        {
            Location = new Point(24, 214),
            Size = new Size(512, 96),
            BackColor = Theme.Panel,
        };
        Controls.Add(panel);

        var l1 = Theme.MakeLabel("حالت اسکن:", Theme.Small, Theme.Muted);
        l1.Location = new Point(420, 12);
        panel.Controls.Add(l1);
        _scan = new ComboBox
        {
            Location = new Point(300, 9), Size = new Size(112, 26),
            DropDownStyle = ComboBoxStyle.DropDownList,
            BackColor = Theme.PanelHi, ForeColor = Theme.Text, FlatStyle = FlatStyle.Flat,
        };
        _scan.Items.AddRange(new object[] { "سریع", "متعادل", "مقاوم", "آهنین" });
        _scan.SelectedIndex = 0;
        panel.Controls.Add(_scan);

        var l2 = Theme.MakeLabel("پوشش/obfuscation:", Theme.Small, Theme.Muted);
        l2.Location = new Point(112, 12);
        panel.Controls.Add(l2);
        _noize = new ComboBox
        {
            Location = new Point(12, 9), Size = new Size(96, 26),
            DropDownStyle = ComboBoxStyle.DropDownList,
            BackColor = Theme.PanelHi, ForeColor = Theme.Text, FlatStyle = FlatStyle.Flat,
        };
        _noize.Items.AddRange(new object[] { "فایروال", "عادی", "شدید (GFW)", "خاموش" });
        _noize.SelectedIndex = 0;
        panel.Controls.Add(_noize);

        _fragment = new CheckBox
        {
            Text = "تکه‌تکه‌کردن ClientHello (ضد DPI ایران)",
            Location = new Point(238, 46),
            AutoSize = true, ForeColor = Theme.Text, Font = Theme.Small, Checked = true,
        };
        panel.Controls.Add(_fragment);

        _noIran = new CheckBox
        {
            Text = "خروج غیر از ایران",
            Location = new Point(378, 46),
            AutoSize = true, ForeColor = Theme.Text, Font = Theme.Small,
        };
        panel.Controls.Add(_noIran);

        _h2 = new CheckBox
        {
            Text = "HTTP/2 (وقتی QUIC/UDP بسته است)",
            Location = new Point(150, 70),
            AutoSize = true, ForeColor = Theme.Text, Font = Theme.Small,
        };
        panel.Controls.Add(_h2);

        var proxyNote = Theme.MakeLabel(
            "اتصال = تونل + پراکسی سیستم (HTTP 127.0.0.1:1818 — SOCKS5 127.0.0.1:1819)",
            Theme.Small, Theme.Muted);
        proxyNote.Location = new Point(24, 318);
        Controls.Add(proxyNote);

        _log.Location = new Point(24, 344);
        _log.Size = new Size(512, 176);
        Controls.Add(_log);

        _tunnel.Log += (line) => Safe(() =>
        {
            _log.AppendText(line + Environment.NewLine);
            if (_log.TextLength > 60_000) _log.Clear();
        });
        _tunnel.StateChanged += (state) => Safe(() =>
        {
            switch (state)
            {
                case "starting":
                    SetStatus("در حال اتصال…", Theme.Amber);
                    _toggle.Enabled = false;
                    break;
                case "connected":
                    SysProxy.Enable();
                    _connected = true;
                    SetStatus("متصل 🍯", Theme.Ok);
                    _dot.ForeColor = Theme.Ok;
                    _toggle.Text = "قطع اتصال";
                    _toggle.Enabled = true;
                    _log.AppendText("── تونل برقرار شد؛ پراکسی سیستم فعال است ──" + Environment.NewLine);
                    break;
                case "failed":
                    SetStatus("اتصال ناموفق — دوباره تلاش کنید", Theme.Danger);
                    _dot.ForeColor = Theme.Danger;
                    _toggle.Text = "اتصال";
                    _toggle.Enabled = true;
                    _log.AppendText("── موتور نتوانست تونل بسازد (حالت مقاوم را امتحان کنید) ──" + Environment.NewLine);
                    break;
                case "stopped":
                    _connected = false;
                    SysProxy.DisableAndRestore();
                    SetStatus("قطع", Theme.Muted);
                    _dot.ForeColor = Theme.Muted;
                    _toggle.Text = "اتصال";
                    _toggle.Enabled = true;
                    break;
            }
        });

        FormClosing += (_, e) =>
        {
            if (_tunnel.IsRunning)
            {
                _tunnel.Stop();
                SysProxy.DisableAndRestore();
            }
        };

        // Silent revalidation on open, like the Android app's resume check.
        _ = Task.Run(async () =>
        {
            bool alive = await _lic.RevalidateAsync();
            if (!alive || !_lic.Entitled())
            {
                Safe(() =>
                {
                    _store.Token = "";
                    _store.Save();
                    Hide();
                    MessageBox.Show("اشتراک این دستگاه منقضی یا باطل شده است. لطفاً دوباره فعال کنید.",
                        "اشتراک", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    Close();
                });
            }
        });
    }

    void SetStatus(string text, Color c)
    {
        _status.Text = text;
        _status.ForeColor = c;
        if (text != "متصل 🍯" && text != "قطع") _dot.ForeColor = c;
    }

    void Safe(Action a)
    {
        try { if (IsHandleCreated) BeginInvoke(a); } catch { /* form gone */ }
    }

    async Task ToggleAsync()
    {
        if (_tunnel.IsRunning)
        {
            _tunnel.Stop();      // StateChanged("stopped") restores the proxy
            return;
        }
        if (!_lic.Entitled())
        {
            MessageBox.Show("اشتراک معتبر نیست — از صفحهٔ پروفایل وضعیت را ببینید.", "BEE-VPN",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        _tunnel.ScanMode = _scan.SelectedIndex switch
        {
            1 => "balanced", 2 => "thorough", 3 => "ironclad", _ => "turbo",
        };
        _tunnel.Noize = _noize.SelectedIndex switch
        {
            1 => "light", 2 => "gfw", 3 => "off", _ => "firewall",
        };
        _tunnel.Fragment = _fragment.Checked;
        _tunnel.UseH2 = _h2.Checked;
        _tunnel.NoIranExit = _noIran.Checked;
        _log.Clear();
        _tunnel.Start();
    }

    static void TryOpen(string url)
    {
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo { FileName = url, UseShellExecute = true }); }
        catch { /* browser missing */ }
    }
}
