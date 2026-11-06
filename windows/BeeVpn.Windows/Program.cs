// BEE-VPN for Windows — entry point: load DPAPI-stored state, restore the
// install id, decide gate-vs-main (same shape as MainActivity + LicenseGate).
using System;
using System.Windows.Forms;

namespace BeeVpn;

static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.EnableVisualStyles();

        var store = TokenStore.Load();
        var lic = new BeeLicense();
        lic.InstallId = store.InstallId;
        lic.Token = store.Token;
        lic.Subject = store.Subject;
        lic.Kind = store.Kind;
        lic.EnsureInstallId(
            () => store.InstallId,
            (id) => { store.InstallId = id; store.Save(); });

        bool hadToken = !string.IsNullOrWhiteSpace(store.Token);
        bool ok = hadToken && lic.Entitled();

        if (!ok)
        {
            using var gate = new GateForm(lic, store, hadToken);
            if (gate.ShowDialog() != DialogResult.OK) return;
        }

        store.Token = lic.Token;
        store.Subject = lic.Subject;
        store.Kind = lic.Kind;
        store.InstallId = lic.InstallId;
        store.Save();

        Application.Run(new MainForm(lic, store));
    }
}
