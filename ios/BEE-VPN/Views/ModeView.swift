import SwiftUI

/// Connection mode screen — the Android openModeScreen: one row per mode
/// with its label, description and badges. Only WireGuard runs on iOS; the
/// other six are shown with an honest "Android-only" badge instead of a
/// pretend toggle.
struct ModeView: View {
    @Environment(\.beePalette) private var palette
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var tunnel = TunnelControllerHolder.shared.controller
    @ObservedObject private var settings = AppSettings.shared

    var body: some View {
        NavigationStack {
            ZStack {
                palette.canvas.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(BeeStrings.t("Choose how BEE-VPN connects"))
                            .font(.system(size: 13))
                            .foregroundColor(palette.muted)
                            .padding(.bottom, 4)
                        ForEach(TunnelMode.allCases) { mode in
                            row(mode)
                        }
                        Text(BeeStrings.t("WireGuard is the only engine on iOS for now"))
                            .font(.system(size: 11))
                            .foregroundColor(palette.muted)
                            .padding(.top, 6)
                    }
                    .padding(14)
                }
            }
            .navigationTitle(BeeStrings.t("Connection mode"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(BeeStrings.t("Done")) { dismiss() }
                        .foregroundColor(palette.primary)
                }
            }
        }
    }

    private func row(_ mode: TunnelMode) -> some View {
        Button {
            if mode.supportedOnIOS {
                tunnel.setMode(mode)
            }
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text(mode.displayName)
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(mode.supportedOnIOS ? palette.ink : palette.muted)
                        if tunnel.mode == mode {
                            badge(BeeStrings.t("CURRENT"), color: palette.ok)
                        }
                        if !mode.supportedOnIOS {
                            badge(BeeStrings.t("Only on Android"), color: palette.muted)
                        }
                    }
                    Text(mode.description)
                        .font(.system(size: 12))
                        .foregroundColor(palette.muted)
                }
                Spacer()
                if mode.supportedOnIOS {
                    Image(systemName: tunnel.mode == mode ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 18))
                        .foregroundColor(tunnel.mode == mode ? palette.primary : palette.muted)
                } else {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 14))
                        .foregroundColor(palette.muted.opacity(0.6))
                }
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 14).fill(palette.surface))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(
                tunnel.mode == mode ? palette.primary.opacity(0.7) : palette.divider, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(!mode.supportedOnIOS)
    }

    private func badge(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.system(size: 10, weight: .bold))
            .foregroundColor(color)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Capsule().stroke(color.opacity(0.6), lineWidth: 1))
    }
}
