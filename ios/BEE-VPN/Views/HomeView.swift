import SwiftUI
import NetworkExtension

/// Home — the honey console: header, the tap-to-connect dial, latency and
/// protocol chips, the three metric tiles, the exit-node card and the
/// transport rail — the same layout Android's createConnectionConsole builds.
struct HomeView: View {
    @Environment(\.beePalette) private var palette
    @ObservedObject private var tunnel = TunnelControllerHolder.shared.controller
    @ObservedObject private var settings = AppSettings.shared

    @State private var trace: IPReporter.Trace?
    @State private var measuring = false
    @State private var showModeSheet = false
    @State private var showSettings = false

    var body: some View {
        ZStack {
            palette.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 14) {
                    header
                    dial
                    statusBlock
                    chips
                    metricTiles
                    exitNodeCard
                    transportRail
                    Text(BeeStrings.t("SECURED BY BEE-VPN"))
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundColor(palette.muted)
                        .padding(.top, 6)
                        .padding(.bottom, 18)
                }
                .padding(.horizontal, 14)
            }
        }
        .sheet(isPresented: $showModeSheet) { ModeView() }
        .fullScreenCover(isPresented: $showSettings) { SettingsView() }
        .task { await measure() }
    }

    // ------------------------------------------------------------ header

    private var header: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(statusColor)
                .frame(width: 10, height: 10)
                .shadow(color: statusColor.opacity(0.6), radius: 4)
            Text("BEE-VPN")
                .font(.system(size: 18, weight: .heavy, design: .rounded))
                .foregroundColor(palette.ink)
            Text(faDigits(AppVersion.short))
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(palette.muted)
            Spacer()
            Button { showSettings = true } label: {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 17))
                    .foregroundColor(palette.muted)
            }
            .buttonStyle(.plain)
        }
        .padding(.top, 6)
    }

    // ------------------------------------------------------------ dial

    private var dial: some View {
        Button {
            Task { await tunnel.toggle() }
        } label: {
            ZStack {
                Circle()
                    .fill(RadialGradient(colors: [statusColor.opacity(0.30), palette.canvas],
                                         center: .center, startRadius: 10, endRadius: 120))
                    .frame(width: 228, height: 228)
                if case .connecting = tunnel.phase {
                    Circle()
                        .trim(from: 0, to: 0.72)
                        .stroke(statusColor.opacity(0.8), style: StrokeStyle(lineWidth: 4, lineCap: .round))
                        .frame(width: 178, height: 178)
                        .rotationEffect(.degrees(rotation))
                        .onAppear { withAnimation(.linear(duration: 1.4).repeatForever(autoreverses: false)) { rotation = 360 } }
                } else {
                    Circle()
                        .stroke(statusColor, lineWidth: 3)
                        .frame(width: 178, height: 178)
                        .shadow(color: statusColor.opacity(0.35), radius: 16)
                }
                VStack(spacing: 4) {
                    Text("🐝").font(.system(size: 52))
                }
            }
        }
        .buttonStyle(.plain)
        .animation(.easeInOut(duration: 0.3), value: tunnel.phase)
    }

    @State private var rotation: Double = 0

    private var statusBlock: some View {
        VStack(spacing: 6) {
            Text(tunnel.statusText)
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(statusColor)
                .multilineTextAlignment(.center)
            if let detail = tunnel.statusDetail {
                Text(detail)
                    .font(.system(size: 12))
                    .foregroundColor(palette.muted)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, 10)
    }

    // ------------------------------------------------------------ chips

    private var chips: some View {
        HStack(spacing: 10) {
            Button { Task { await measure() } } label: {
                chip(
                    icon: "dot.radiowaves.left.and.right",
                    text: latencyText,
                    color: latencyColor)
            }
            .buttonStyle(.plain)
            chip(
                icon: "arrow.triangle.swap",
                text: tunnel.mode.railLabel.uppercased(),
                color: palette.primary,
                action: { showModeSheet = true })
            if case .failed = tunnel.phase {
                chip(icon: "arrow.triangle.2.circlepath",
                     text: BeeStrings.t("Auto Scan"),
                     color: palette.danger,
                     action: { Task { await tunnel.runAutoScan() } })
            }
        }
    }

    private func chip(icon: String, text: String, color: Color, action: (() -> Void)? = nil) -> some View {
        Group {
            if let action {
                Button(action: action) { chipBody(icon: icon, text: text, color: color) }
                    .buttonStyle(.plain)
            } else {
                chipBody(icon: icon, text: text, color: color)
            }
        }
    }

    private func chipBody(icon: String, text: String, color: Color) -> some View {
        HStack(spacing: 6) {
            Image(systemName: icon)
                .font(.system(size: 11))
            Text(text)
                .font(.system(size: 13, weight: .semibold))
        }
        .foregroundColor(color)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Capsule().fill(palette.surface))
        .overlay(Capsule().stroke(palette.divider, lineWidth: 1))
    }

    private var latencyText: String {
        if measuring { return BeeStrings.t("measuring…") }
        if let rtt = trace?.rttMs {
            return BeeStrings.t("Latency") + " " + faDigits("\(rtt)") + " ms"
        }
        return BeeStrings.t("Latency —")
    }

    private var latencyColor: Color {
        guard let rtt = trace?.rttMs else { return palette.muted }
        if rtt < 250 { return palette.ok }
        if rtt < 700 { return palette.primary }
        return palette.danger
    }

    // ------------------------------------------------------------ metric tiles

    private var metricTiles: some View {
        HStack(spacing: 10) {
            metricTile(icon: "arrow.down.circle.fill", title: BeeStrings.t("↓ DOWN"), value: "—")
            metricTile(icon: "arrow.up.circle.fill", title: BeeStrings.t("↑ UP"), value: "—")
            metricTile(icon: "gauge.with.dial.needle.50percent",
                       title: BeeStrings.t("SPEED"),
                       value: sessionDuration)
        }
    }

    /// Byte counters belong to the Android core engine's stats surface; the
    /// WireGuard iOS adapter does not expose them, so the tiles show the
    /// session duration and honest dashes instead of invented numbers.
    private var sessionDuration: String {
        guard let start = tunnel.connectDate else { return "—" }
        let interval = Int(Date().timeIntervalSince(start))
        let h = interval / 3600, m = (interval % 3600) / 60, s = interval % 60
        return h > 0
            ? faDigits(String(format: "%d:%02d:%02d", h, m, s))
            : faDigits(String(format: "%02d:%02d", m, s))
    }

    private func metricTile(icon: String, title: String, value: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: icon)
                .font(.system(size: 15))
                .foregroundColor(palette.muted)
            Text(value)
                .font(.system(size: 15, weight: .bold, design: .monospaced))
                .foregroundColor(palette.ink)
            Text(title)
                .font(.system(size: 10, weight: .semibold))
                .foregroundColor(palette.muted)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 14).fill(palette.surface))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(palette.divider, lineWidth: 1))
    }

    // ------------------------------------------------------------ exit node card

    private var exitNodeCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(BeeStrings.t("EXIT NODE"))
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(palette.muted)
                Spacer()
                Button { Task { await measure() } } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 12))
                        .foregroundColor(palette.muted)
                }
                .buttonStyle(.plain)
            }
            if tunnel.isConnected {
                if let trace, measuring == false {
                    HStack(spacing: 8) {
                        Text(IPReporter.flag(for: trace.location))
                            .font(.system(size: 26))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(trace.ip)
                                .font(.system(size: 15, weight: .semibold, design: .monospaced))
                                .foregroundColor(palette.ink)
                            Text(BeeStrings.tf("%s · tunnelled", trace.location))
                                .font(.system(size: 11))
                                .foregroundColor(palette.ok)
                        }
                        Spacer()
                    }
                } else {
                    Text(BeeStrings.t("measuring…"))
                        .font(.system(size: 13))
                        .foregroundColor(palette.muted)
                }
            } else {
                Text(BeeStrings.t("not tunnelled"))
                    .font(.system(size: 13))
                    .foregroundColor(palette.muted)
            }
            Text(BeeStrings.t("tap to refresh"))
                .font(.system(size: 10))
                .foregroundColor(palette.muted.opacity(0.7))
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(palette.surface))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(palette.divider, lineWidth: 1))
        .onTapGesture { Task { await measure() } }
    }

    // ------------------------------------------------------------ transport rail

    private var transportRail: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(BeeStrings.t("Connection mode"))
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(palette.muted)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(TunnelMode.allCases) { mode in
                        Button {
                            if mode.supportedOnIOS {
                                tunnel.setMode(mode)
                            } else {
                                showModeSheet = true
                            }
                        } label: {
                            VStack(spacing: 3) {
                                Text(mode.railLabel)
                                    .font(.system(size: 13, weight: .bold))
                                Circle()
                                    .fill(mode.supportedOnIOS ? palette.ok : palette.muted.opacity(0.35))
                                    .frame(width: 5, height: 5)
                            }
                            .foregroundColor(tunnel.mode == mode ? palette.canvas : palette.ink)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .background(
                                Capsule().fill(tunnel.mode == mode ? palette.primary : palette.surface))
                            .overlay(Capsule().stroke(tunnel.mode == mode ? Color.clear : palette.divider, lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, 2)
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private func measure() async {
        measuring = true
        let result = await IPReporter.trace(timeout: tunnel.isConnected ? 12 : 8)
        trace = result
        measuring = false
    }

    private var statusColor: Color {
        switch tunnel.phase {
        case .connected: return palette.ok
        case .connecting: return palette.primary
        case .degraded: return palette.primary
        case .failed: return palette.danger
        case .idle: return palette.muted
        }
    }
}

/// One shared controller so the tab, the mode sheet and the settings sheet
/// see the same status. TunnelController is @MainActor, so the holder is too.
@MainActor
final class TunnelControllerHolder {
    static let shared = TunnelControllerHolder()
    let controller = TunnelController()
}
