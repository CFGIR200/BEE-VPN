import SwiftUI

/// Home — the honey orb connect button and live tunnel status.
struct HomeView: View {
    @ObservedObject private var tunnel = TunnelControllerHolder.shared
    @State private var busy = false

    var body: some View {
        ZStack {
            Honey.canvas.ignoresSafeArea()
            VStack(spacing: 28) {
                Spacer()
                orb
                Text(tunnel.statusText)
                    .font(.system(size: 19, weight: .bold))
                    .foregroundColor(statusColor)
                if let error = tunnel.lastError {
                    Text(error)
                        .font(.system(size: 13))
                        .foregroundColor(Honey.danger)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                }
                Spacer()
                HoneyButton(
                    title: connectTitle,
                    filled: true
                ) {
                    Task { await toggle() }
                }
                .disabled(busy)
                .padding(.horizontal, 32)
                Text("ربات فروش و خرید اشتراک: @BEESELL_GP")
                    .font(.system(size: 12))
                    .foregroundColor(Honey.muted)
            }
            .padding(.vertical, 24)
        }
        .preferredColorScheme(.dark)
    }

    private var connectTitle: String {
        if busy { return "…" }
        return tunnel.isConnected || tunnel.status == .connecting ? "قطع اتصال" : "اتصال"
    }

    private var statusColor: Color {
        switch tunnel.status {
        case .connected: return Honey.ok
        case .connecting, .disconnecting, .reasserting: return Honey.primary
        case .invalid: return Honey.muted
        default: return Honey.ink
        }
    }

    private var orb: some View {
        ZStack {
            Circle()
                .fill(RadialGradient(colors: [statusColor.opacity(0.28), Honey.canvas],
                                     center: .center, startRadius: 10, endRadius: 120))
                .frame(width: 240, height: 240)
            Circle()
                .stroke(statusColor, lineWidth: 3)
                .frame(width: 172, height: 172)
                .shadow(color: statusColor.opacity(0.35), radius: 18)
            Text("🐝")
                .font(.system(size: 64))
        }
        .animation(.easeInOut(duration: 0.3), value: tunnel.status)
    }

    @MainActor
    private func toggle() async {
        busy = true
        defer { busy = false }
        if tunnel.isConnected || tunnel.status == .connecting {
            tunnel.disconnect()
        } else {
            await tunnel.connectOrProvision()
        }
    }
}

/// One shared controller so the tab and any sheet see the same status.
/// TunnelController is @MainActor, so the holder must be too.
@MainActor
final class TunnelControllerHolder {
    static let shared = TunnelController()
}
