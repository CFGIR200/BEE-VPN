import SwiftUI

/// Advanced — the identity card and the manual wg-quick escape hatch, plus
/// the about card. This is the iOS counterpart of the Warp Setting identity
/// tools; the manual paste stays the fallback when registration is blocked.
struct AdvancedView: View {
    @Environment(\.beePalette) private var palette
    @ObservedObject private var tunnel = TunnelControllerHolder.shared.controller

    @State private var manualText = ""
    @State private var note: String?
    @State private var busy = false

    var body: some View {
        ZStack {
            palette.canvas.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    identityCard
                    manualCard
                    aboutCard
                }
                .padding(14)
            }
        }
        .onAppear { manualText = WarpProvisioner.manualConfig() ?? "" }
    }

    // ------------------------------------------------------------ identity

    private var identityCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(BeeStrings.t("Identity Vault"))
                .font(.system(size: 11, weight: .heavy))
                .foregroundColor(palette.muted)
            if let identity = WarpProvisioner.saved() {
                InfoRow(title: BeeStrings.t("ID"), value: String(identity.deviceId.prefix(12)))
                InfoRow(title: "Endpoint", value: identity.endpoint)
            } else {
                Text(BeeStrings.t("No identity yet"))
                    .font(.system(size: 13))
                    .foregroundColor(palette.muted)
            }
            HoneyButton(title: "🏗 " + BeeStrings.t("New account")) {
                Task {
                    busy = true
                    note = BeeStrings.t("Registration started…")
                    await tunnel.regenerateIdentity()
                    note = BeeStrings.t("Identity regenerated")
                    busy = false
                }
            }
            .disabled(busy)
        }
        .honeyCard()
    }

    // ------------------------------------------------------------ manual

    private var manualCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(BeeStrings.t("Manual endpoint"))
                .font(.system(size: 11, weight: .heavy))
                .foregroundColor(palette.muted)
            Text(BeeStrings.t("If the operator blocks the automatic WARP registration, a manual wg-quick config wins. Clear it and save to go back to automatic."))
                .font(.system(size: 12))
                .foregroundColor(palette.muted)
            MonoEditor(text: $manualText)
                .frame(minHeight: 180)
                .padding(8)
                .background(RoundedRectangle(cornerRadius: 12).fill(palette.track))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.divider, lineWidth: 1))
            HStack(spacing: 10) {
                HoneyButton(title: BeeStrings.t("Save")) {
                    WarpProvisioner.setManualConfig(manualText)
                    note = manualText.trimmingCharacters(in: .whitespaces).isEmpty
                        ? BeeStrings.t("Cleared")
                        : BeeStrings.t("Saved")
                    BeeLog.write("manual wg-quick \(manualText.isEmpty ? "cleared" : "saved")")
                }
                HoneyButton(title: BeeStrings.t("Clear and go Direct"), filled: false) {
                    manualText = ""
                    WarpProvisioner.setManualConfig(nil)
                    note = BeeStrings.t("Cleared")
                }
            }
        }
        .honeyCard()
    }

    // ------------------------------------------------------------ about

    private var aboutCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(BeeStrings.t("ABOUT"))
                .font(.system(size: 11, weight: .heavy))
                .foregroundColor(palette.muted)
            InfoRow(title: BeeStrings.t("Version"), value: "BEE-VPN " + faDigits(AppVersion.short))
            Text(BeeStrings.tf("خرید اشتراک: %s", handle(of: BeeConfig.telegramURL)))
                .font(.system(size: 12))
                .foregroundColor(palette.muted)
            Text(BeeStrings.tf("پشتیبانی: %s", handle(of: BeeConfig.supportURL)))
                .font(.system(size: 12))
                .foregroundColor(palette.muted)
            Link(BeeStrings.t("Source on GitHub"),
                 destination: URL(string: "https://github.com/CFGIR200/BEE-VPN")!)
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(palette.primary)
            Text(BeeStrings.t("Based on BEEHIVE-GUARD - AGPL-3.0"))
                .font(.system(size: 10))
                .foregroundColor(palette.muted.opacity(0.7))
        }
        .honeyCard()
    }

    private func handle(of url: String) -> String {
        URL(string: url)?.lastPathComponent ?? url
    }
}
