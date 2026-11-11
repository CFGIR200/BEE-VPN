import SwiftUI
import Combine

/// Traffic monitor — Android's openTrafficMonitorScreen. The WireGuard iOS
/// adapter does not expose byte counters, so the live-speed and session
/// cards show the session clock plus honest dashes, with the exact note the
/// Android screen prints about encrypted tunnel counters.
struct TrafficMonitorView: View {
    @Environment(\.beePalette) private var palette
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var tunnel = TunnelControllerHolder.shared.controller

    let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            ZStack {
                palette.canvas.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        card(title: BeeStrings.t("LIVE SPEED")) {
                            HStack(spacing: 20) {
                                speedColumn(icon: "arrow.down.circle.fill", label: BeeStrings.t("↓ DOWN"), value: "—")
                                speedColumn(icon: "arrow.up.circle.fill", label: BeeStrings.t("↑ UP"), value: "—")
                            }
                        }
                        card(title: BeeStrings.t("THIS SESSION")) {
                            VStack(alignment: .leading, spacing: 8) {
                                HStack {
                                    Text(BeeStrings.t("Duration"))
                                        .font(.system(size: 12))
                                        .foregroundColor(palette.muted)
                                    Spacer()
                                    Text(duration)
                                        .font(.system(size: 20, weight: .bold, design: .monospaced))
                                        .foregroundColor(palette.ink)
                                }
                                Text(tunnel.isConnected
                                     ? BeeStrings.t("Connected")
                                     : BeeStrings.t("Waiting for tunnel traffic"))
                                    .font(.system(size: 12))
                                    .foregroundColor(tunnel.isConnected ? palette.ok : palette.muted)
                            }
                        }
                        Text(BeeStrings.t("Traffic carried by BEE-VPN. Per-app attribution is not available from encrypted tunnel counters."))
                            .font(.system(size: 11))
                            .foregroundColor(palette.muted.opacity(0.85))
                        Spacer()
                    }
                    .padding(14)
                }
            }
            .navigationTitle(BeeStrings.t("Traffic monitor"))
            .navigationBarTitleDisplayMode(.inline)
            .onReceive(tick) { _ in }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(BeeStrings.t("Done")) { dismiss() }
                        .foregroundColor(palette.primary)
                }
            }
        }
    }

    private func card(title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.system(size: 11, weight: .heavy))
                .foregroundColor(palette.muted)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(palette.surface))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(palette.divider, lineWidth: 1))
    }

    private func speedColumn(icon: String, label: String, value: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundColor(palette.muted)
            VStack(alignment: .leading, spacing: 2) {
                Text(value)
                    .font(.system(size: 17, weight: .bold, design: .monospaced))
                    .foregroundColor(palette.ink)
                Text(label)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(palette.muted)
            }
        }
    }

    private var duration: String {
        guard let start = tunnel.connectDate else { return faDigits("00:00") }
        let interval = max(0, Int(Date().timeIntervalSince(start)))
        let h = interval / 3600, m = (interval % 3600) / 60, s = interval % 60
        return faDigits(String(format: "%d:%02d:%02d", h, m, s))
    }
}
