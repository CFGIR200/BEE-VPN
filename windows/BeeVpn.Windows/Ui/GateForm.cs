// BEE-VPN for Windows — the activation gate. Mirrors LicenseGateActivity:
// «کندو قفله 🐝», one code box or one username/password pair, buy link to the
// Telegram bot. Nothing else in the app opens until this passes.
using System;
using System.Drawing;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace BeeVpn;

public sealed class GateForm : Form
{
    readonly BeeLicense _lic;
    readonly TokenStore _store;
    readonly bool _hadToken;                       // true = a token existed but is dead/expired
    readonly RadioButton _rbCode, _rbAccount;
    readonly TextBox _code, _user, _pass;
    readonly Button _go;
    readonly Label _status;

    public GateForm(BeeLicense lic, TokenStore store, bool hadToken)
    {
        _lic = lic; _store = store; _hadToken = hadToken;

        Text = "BEE-VPN — فعال‌سازی اشتراک";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false; MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(520, 470);
        BackColor = Theme.Bg;
        Font = Theme.Body;
        RightToLeft = RightToLeft.Yes;
        RightToLeftLayout = true;

        var bee = Theme.MakeLabel("🐝", new Font("Segoe UI Emoji", 30f));
        bee.Location = new Point(240, 14);
        Controls.Add(bee);

        var title = Theme.MakeLabel("کندو قفله!", Theme.Title, Theme.Amber);
        title.Location = new Point(185, 84);
        Controls.Add(title);

        var sub = Theme.MakeLabel("اشتراک BEE-VPN را فعال کنید تا کندو باز شود 🍯", Theme.Body, Theme.Muted);
        sub.Location = new Point(96, 118);
        Controls.Add(sub);

        _rbCode = new RadioButton
        {
            Text = "🔑 کد فعال‌سازی",
            Location = new Point(330, 165),
            AutoSize = true,
            ForeColor = Theme.Text,
            Font = Theme.H2,
            Checked = true,
        };
        _rbCode.CheckedChanged += (_, _) => SwitchMode();
        Controls.Add(_rbCode);

        _rbAccount = new RadioButton
        {
            Text = "👤 حساب کاربری",
            Location = new Point(110, 165),
            AutoSize = true,
            ForeColor = Theme.Text,
            Font = Theme.H2,
        };
        Controls.Add(_rbAccount);

        _code = new TextBox
        {
            Location = new Point(120, 205),
            Size = new Size(280, 30),
            Anchor = AnchorStyles.Top,
            BackColor = Theme.Panel,
            ForeColor = Theme.Text,
            BorderStyle = BorderStyle.FixedSingle,
            Font = Theme.H2,
            CharacterCasing = CharacterCasing.Upper,
            PlaceholderText = "BEE-XXXX-XXXX-XXXX",
        };
        Controls.Add(_code);

        _user = new TextBox
        {
            Location = new Point(120, 205),
            Size = new Size(280, 30),
            BackColor = Theme.Panel,
            ForeColor = Theme.Text,
            BorderStyle = BorderStyle.FixedSingle,
            Visible = false,
            PlaceholderText = "نام کاربری",
        };
        Controls.Add(_user);

        _pass = new TextBox
        {
            Location = new Point(120, 243),
            Size = new Size(280, 30),
            BackColor = Theme.Panel,
            ForeColor = Theme.Text,
            BorderStyle = BorderStyle.FixedSingle,
            Visible = false,
            UseSystemPasswordChar = true,
            PlaceholderText = "رمز عبور",
        };
        Controls.Add(_pass);

        _go = Theme.HoneyButton("فعال‌سازی / ورود", 280, 46);
        _go.Location = new Point(120, 300);
        _go.Click += async (_, _) => await GoAsync();
        Controls.Add(_go);

        _status = Theme.MakeLabel("", Theme.Body, Theme.Danger);
        _status.Location = new Point(40, 352);
        _status.Size = new Size(440, 40);
        _status.TextAlign = ContentAlignment.MiddleCenter;
        Controls.Add(_status);

        var buy = Theme.GhostButton("🛍 خرید اشتراک — ربات تلگرام", 280, 38);
        buy.Location = new Point(120, 396);
        buy.Click += (_, _) => Support();
        Controls.Add(buy);

        if (_hadToken)
            _status.Text = "اشتراک این دستگاه منقضی یا باطل شده — دوباره فعال کنید";

        AcceptButton = _go;
        _code.TextChanged += (_, _) => { _code.Text = _code.Text.ToUpperInvariant(); _code.SelectionStart = _code.Text.Length; };
        _code.KeyDown += (_, e) => { if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; _ = GoAsync(); } };
    }

    void SwitchMode()
    {
        bool code = _rbCode.Checked;
        _code.Visible = code;
        _user.Visible = _pass.Visible = !code;
        if (!code) _user.Focus();
    }

    async Task GoAsync()
    {
        _go.Enabled = false;
        _status.ForeColor = Theme.Muted;
        _status.Text = "در حال بررسی…";
        try
        {
            bool ok;
            if (_rbCode.Checked)
            {
                string c = _code.Text.Trim();
                if (!System.Text.RegularExpressions.Regex.IsMatch(c, @"^BEE-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$"))
                {
                    _status.ForeColor = Theme.Danger;
                    _status.Text = "قالب کد درست نیست — مثال: BEE-XXXX-XXXX-XXXX";
                    _go.Enabled = true;
                    return;
                }
                ok = await _lic.ActivateWithCodeAsync(c, Environment.MachineName);
            }
            else
            {
                ok = await _lic.LoginAsync(_user.Text.Trim(), _pass.Text);
            }

            if (ok && _lic.Entitled())
            {
                _store.Token = _lic.Token;
                _store.Subject = _lic.Subject;
                _store.Kind = _lic.Kind;
                _store.InstallId = _lic.InstallId;
                _store.Save();
                DialogResult = DialogResult.OK;
                Close();
            }
            else
            {
                _status.ForeColor = Theme.Danger;
                _status.Text = string.IsNullOrWhiteSpace(_lic.LastError) ? "فعال‌سازی ناموفق بود" : _lic.LastError;
            }
        }
        finally
        {
            _go.Enabled = true;
        }
    }

    void Support()
    {
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
        {
            FileName = "https://t.me/Bee_hive_VPN",
            UseShellExecute = true,
        }); }
        catch { /* browser missing */ }
    }
}
