import SwiftUI

/// Advanced — identity management and the manual wg-quick escape hatch for
/// hostile carriers (the iOS counterpart of the Android Warp Setting section).
struct AdvancedView: View {
    @ObservedObject private var tunnel = TunnelControllerHolder.shared
    @State private var manual = ""
    @State private var savedMessage: String?
    @State private var busy = false

    var body: some View {
        ZStack {
            Honey.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    identityCard
                    manualCard
                    aboutCard
                }
                .padding(24)
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            manual = WarpProvisioner.manualConfig() ?? ""
        }
    }

    private var identityCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("هویت WARP")
                .font(.system(size: 15, weight: .bold))
                .foregroundColor(Honey.ink)
            let idText: String = {
                if let id = WarpProvisioner.saved()?.deviceId, !id.isEmpty {
                    return String(id.prefix(18)) + "…"
                }
                return "ثبت‌نام نشده — با اولین اتصال ساخته می‌شود"
            }()
            InfoRow(title: "شناسهٔ ثبت‌شده", value: idText)
            InfoRow(title: "Endpoint", value: WarpProvisioner.saved()?.endpoint ?? WarpProvisioner.defaultEndpoint)
            HoneyButton(title: busy ? "…" : "🏗 هویت جدید", filled: false) {
                Task {
                    busy = true
                    defer { busy = false }
                    await tunnel.regenerateIdentity()
                    savedMessage = "هویت جدید ساخته شد — اتصال بعدی با آن برقرار می‌شود"
                }
            }
            if let savedMessage {
                Text(savedMessage)
                    .font(.system(size: 12.5))
                    .foregroundColor(Honey.ok)
            }
        }
        .honeyCard()
    }

    private var manualCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("تنظیم دستی (wg-quick)")
                .font(.system(size: 15, weight: .bold))
                .foregroundColor(Honey.ink)
            Text("اگر اپراتورت ثبت‌نام خودکار WARP را می‌بندد، یک کانفیگ WireGuard دستی بچسبان — اولویت با همین است. برای برگشت به حالت خودکار، خالی‌اش کن و ذخیره بزن.")
                .font(.system(size: 12.5))
                .foregroundColor(Honey.muted)
            MonoEditor(text: $manual)
                .font(.system(size: 12, design: .monospaced))
                .foregroundColor(Honey.ink)
                .frame(minHeight: 150)
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 12).fill(Honey.track))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Honey.divider, lineWidth: 1))
            HStack(spacing: 10) {
                HoneyButton(title: "ذخیره", filled: true) {
                    WarpProvisioner.setManualConfig(manual)
                    savedMessage = manual.isEmpty ? "تنظیم دستی پاک شد" : "تنظیم دستی ذخیره شد"
                }
                HoneyButton(title: "پاک‌کردن", filled: false) {
                    manual = ""
                    WarpProvisioner.setManualConfig(nil)
                    savedMessage = "تنظیم دستی پاک شد"
                }
            }
        }
        .honeyCard()
    }

    private var aboutCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("درباره")
                .font(.system(size: 15, weight: .bold))
                .foregroundColor(Honey.ink)
            InfoRow(title: "نسخه", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "3.4.0")
            InfoRow(title: "خرید اشتراک", value: "@BEESELL_GP")
            InfoRow(title: "پشتیبانی", value: "@Bee_hive_VPN")
        }
        .honeyCard()
    }
}
