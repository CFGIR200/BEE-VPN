// BEE-VPN for Windows — honey theme + Vazirmatn fonts, carried over from the
// Android app's palette (#FFC107 amber on #04070B dark) and res/font TTFs.
using System;
using System.Drawing;
using System.Drawing.Text;
using System.IO;
using System.Reflection;

namespace BeeVpn;

public static class Theme
{
    public static readonly Color Bg       = Color.FromArgb(4, 7, 11);
    public static readonly Color Panel    = Color.FromArgb(15, 23, 32);
    public static readonly Color PanelHi  = Color.FromArgb(24, 34, 46);
    public static readonly Color Amber    = Color.FromArgb(255, 193, 7);
    public static readonly Color AmberDim = Color.FromArgb(120, 90, 10);
    public static readonly Color Text     = Color.FromArgb(233, 237, 242);
    public static readonly Color Muted    = Color.FromArgb(148, 158, 168);
    public static readonly Color Ok       = Color.FromArgb(67, 181, 129);
    public static readonly Color Danger   = Color.FromArgb(235, 77, 61);

    public static Font Title, Big, H2, Body, Small, Button, Mono;

    static Theme()
    {
        using var pfc = new PrivateFontCollection();
        string tmpDir = Path.Combine(Path.GetTempPath(), "BEE-VPN");
        Directory.CreateDirectory(tmpDir);
        string regularPath = Path.Combine(tmpDir, "Vazirmatn-Regular.ttf");
        string boldPath = Path.Combine(tmpDir, "Vazirmatn-Bold.ttf");
        File.WriteAllBytes(regularPath, ResourceBytes("BeeVpn.fonts.Vazirmatn-Regular.ttf"));
        File.WriteAllBytes(boldPath, ResourceBytes("BeeVpn.fonts.Vazirmatn-Bold.ttf"));
        pfc.AddFontFile(regularPath);
        pfc.AddFontFile(boldPath);
        string regular = pfc.Families[0].Name;
        string bold = pfc.Families.Length > 1 ? pfc.Families[1].Name : regular;
        Title = new Font(bold, 17f, FontStyle.Bold);
        Big   = new Font(bold, 26f, FontStyle.Bold);
        H2    = new Font(bold, 12.5f, FontStyle.Bold);
        Body  = new Font(regular, 10.5f, FontStyle.Regular);
        Small = new Font(regular, 9f, FontStyle.Regular);
        Button = new Font(bold, 11.5f, FontStyle.Bold);
        Mono  = new Font("Consolas", 8.5f, FontStyle.Regular);
    }

    static byte[] ResourceBytes(string name)
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name)
            ?? throw new InvalidOperationException("embedded resource missing: " + name);
        using var ms = new MemoryStream();
        s.CopyTo(ms);
        return ms.ToArray();
    }

    public static Button HoneyButton(string text, int w = 220, int h = 44)
    {
        var b = new Button
        {
            Text = text,
            Width = w,
            Height = h,
            FlatStyle = FlatStyle.Flat,
            BackColor = Amber,
            ForeColor = Color.FromArgb(30, 22, 2),
            Font = Button,
            Cursor = System.Windows.Forms.Cursors.Hand,
        };
        b.FlatAppearance.BorderSize = 0;
        return b;
    }

    public static Button GhostButton(string text, int w = 220, int h = 38)
    {
        var b = new Button
        {
            Text = text,
            Width = w,
            Height = h,
            FlatStyle = FlatStyle.Flat,
            BackColor = Panel,
            ForeColor = Text,
            Font = Body,
            Cursor = System.Windows.Forms.Cursors.Hand,
        };
        b.FlatAppearance.BorderColor = AmberDim;
        b.FlatAppearance.BorderSize = 1;
        return b;
    }

    public static Label MakeLabel(string text, Font f, Color? c = null)
        => new()
        {
            Text = text,
            AutoSize = true,
            Font = f,
            ForeColor = c ?? Text,
            BackColor = Color.Transparent,
        };

    public static void Dark(Control.ControlCollection controls, Color? bg = null)
    {
        foreach (Control c in controls)
        {
            if (c is Button) continue;
            c.BackColor = bg ?? Bg;
            Dark(c.Controls, bg);
        }
    }
}
