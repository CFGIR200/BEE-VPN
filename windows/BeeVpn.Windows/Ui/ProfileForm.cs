// BEE-VPN for Windows — the buyer profile, the mirror of the new bot
// «👤 پروفایل من» view: plan, Jalali activation/expiry dates, days remaining,
// device binding, plus revalidate-now and sign-out. Data comes from the same
// entitlement token the Android app holds, refreshed through /verify.
using System;
using System.Drawing;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace BeeVpn;

public sealed class ProfileForm : Form
{
    readonly BeeLicense _lic;
    readonly Action _onSignedOut;

    readonly Label _body = new()
    {
        Font = Theme.Body,
        ForeColor = Theme.Text,
        Location = new Point(24, 120),
        Size = new Size(452, 190),
    };

    readonly Label _days = Theme.MakeLabel("", Theme.Big, Theme.Amber);
    readonly ProgressBar _bar = new()
    {
        Location = new Point(24, 78),
        Size = new Size(452, 14),
        Style = ProgressBarStyle.Continuous,
    };

    public ProfileForm(BeeLicense lic, Action onSignedOut)
    {
        _lic = lic;
        _onSignedOut = onSignedOut;

        Text = "BEE-VPN — پروفایل اشتراک";
        ClientSize = new Size(500, 560);
        BackColor = Theme.Bg;
        Font = Theme.Body;
        RightToLeft = RightToLeft.Yes;
        RightToLeftLayout = true;
        StartPosition = FormStartPosition.CenterParent;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;

        var bee = Theme.MakeLabel("👤 پروفایل من", Theme.Title, Theme.Amber);
        bee.Location = new Point(160, 18);
        Controls.Add(bee);

        _bar.Value = 0;
        Controls.Add(_bar);

        _days.Location = new Point(24, 96);
        Controls.Add(_days);

        Controls.Add(_body);

        var refresh = Theme.GhostButton("🔄 بروزرسانی", 145, 40);
        refresh.Location = new Point(330, 490);
        refresh.Click += async (_, _) => await RefreshAsync();
        Controls.Add(refresh);

        var signOut = Theme.GhostButton("🚪 خروج از حساب", 145, 40);
        signOut.Location = new Point(24, 490);
        signOut.Click += (_, _) =>
        {
            if (MessageBox.Show("از این حساب خارج می‌شوید؟ برای ورود دوباره به کد فعال‌سازی نیاز دارید.",
                    "خروج از حساب", MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes)
            {
                _lic.SignOut();
                _onSignedOut?.Invoke();
                Close();
            }
        };
        Controls.Add(signOut);

        var support = Theme.GhostButton("🆘 پشتیبانی تلگرام", 200, 40);
        support.Location = new Point(150, 440);
        support.Click += (_, _) => TryOpen("https://t.me/Bee_hive_VPN");
        Controls.Add(support);

        var note = Theme.MakeLabel("ℹ️ SOCKS5: 127.0.0.1:1819 — HTTP: 127.0.0.1:1818", Theme.Small, Theme.Muted);
        note.Location = new Point(24, 520);
        Controls.Add(note);

        Shown += async (_, _) => await RefreshAsync();
    }

    async Task RefreshAsync()
    {
        _body.Text = "در حال دریافت وضعیت از سرور…";
        await _lic.RevalidateAsync();
        Render();
    }

    void Render()
    {
        string planTitle = _lic.Plan switch
        {
            "trial" => "🎁 اشتراک تست ۱ روزه",
            "m1" => "اشتراک یک‌ماهه (m1)",
            "m3" => "اشتراک سه‌ماهه (m3)",
            "m6" => "اشتراک شش‌ماهه (m6)",
            "m12" => "اشتراک دوازده‌ماهه (m12)",
            "" => "—",
            var p => p,
        };
        string kind = _lic.Kind switch
        {
            "account" => "👤 حساب کاربری",
            _ => "🔑 کد فعال‌سازی",
        };
        bool expired = _lic.Expiry > 0 && _lic.Expiry <= DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        string status = string.IsNullOrWhiteSpace(_lic.Token)
            ? "⛔ توکن موجود نیست"
            : expired ? "🔴 منقضی شده" : "🟢 فعال";

        var sb = new System.Text.StringBuilder();
        sb.AppendLine($"{kind}: {_lic.Subject}");
        sb.AppendLine($"🏷 پلن: {planTitle}");
        sb.AppendLine($"وضعیت: {status}");
        sb.AppendLine();
        if (_lic.ActivatedAt is long act)
            sb.AppendLine($"📅 فعال‌سازی: {Jalali.FaDateTimeEpoch(act)}");
        if (_lic.Expiry > 0)
        {
            sb.AppendLine($"⏳ انقضا: {Jalali.FaDateTimeEpoch(_lic.Expiry)}");
            sb.AppendLine($"📱 دستگاه: متصل ({Short(_lic.InstallId)}…)");
        }
        else
        {
            sb.AppendLine("⏳ هنوز فعال نشده — از لحظهٔ اولین اتصال در اپ، دوره شروع می‌شود.");
        }
        _body.Text = sb.ToString();

        int days = _lic.DaysRemaining();
        if (expired || (days == 0 && _lic.Expiry > 0))
        {
            _days.Text = "اشتراک منقضی است — تمدید کنید";
            _days.ForeColor = Theme.Danger;
            _bar.Value = 0;
        }
        else if (_lic.Expiry > 0)
        {
            _days.Text = $"{Jalali.Fa(days)} روز باقی‌مانده";
            _days.ForeColor = days <= 7 ? Theme.Danger : Theme.Ok;
            // Subscription progress: from activation (or issued-at fallback) to expiry.
            long start = _lic.ActivatedAt ?? (_lic.Expiry - 30L * 86400);
            long span = Math.Max(1, _lic.Expiry - start);
            long left = Math.Max(0, _lic.Expiry - DateTimeOffset.UtcNow.ToUnixTimeSeconds());
            int pct = (int)Math.Clamp(left * 100 / span, 0, 100);
            _bar.Value = pct;
        }
        else
        {
            _days.Text = "بدون انقضای ثبت‌شده";
            _days.ForeColor = Theme.Muted;
        }

        static string Short(string s) => string.IsNullOrEmpty(s) ? "—" : s[..Math.Min(10, s.Length)];
    }

    static void TryOpen(string url)
    {
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo { FileName = url, UseShellExecute = true }); }
        catch { /* browser missing */ }
    }
}
