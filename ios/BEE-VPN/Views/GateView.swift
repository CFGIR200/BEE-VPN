import SwiftUI
import UIKit

/// The activation gate — two tabs (activation code / account), the same flow,
/// copy and honey palette as LicenseGateActivity on Android, including the
/// three links: buy, trial, support.
struct GateView: View {
    @Environment(\.beePalette) private var palette

    // Not private: the memberwise initializer must stay internal for RootView.
    @ObservedObject var license = LicenseManager.shared

    /// Why the user was kicked back to the gate (revoked / expired) — shown
    /// like the Android gate's honesty banner.
    var notice: String?

    enum Tab { case code, account }
    @State private var tab: Tab = .code
    @State private var code = ""
    @State private var username = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        ZStack {
            palette.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 18) {
                    Text("🐝")
                        .font(.system(size: 44))
                    Text("BEE-VPN")
                        .font(.system(size: 28, weight: .heavy))
                        .foregroundColor(palette.primary)
                    Text(BeeStrings.t("Activate your subscription to use BEE-VPN"))
                        .font(.system(size: 15))
                        .foregroundColor(palette.ink)

                    picker
                    if let notice {
                        Text(notice)
                            .font(.system(size: 13.5, weight: .semibold))
                            .foregroundColor(palette.danger)
                            .multilineTextAlignment(.center)
                    }
                    inputCard
                    if let error {
                        Text(error)
                            .font(.system(size: 13.5))
                            .foregroundColor(palette.danger)
                            .multilineTextAlignment(.center)
                    }
                    HoneyButton(title: busy ? BeeStrings.t("Activating…") : BeeStrings.t("Activate"), filled: true) {
                        Task { await submit() }
                    }
                    .disabled(busy)

                    HoneyButton(title: BeeStrings.t("🛍 Buy subscription"), filled: false) {
                        open(BeeConfig.telegramURL)
                    }
                    HoneyButton(title: BeeStrings.t("🎁 1-day free trial"), filled: false) {
                        open(BeeConfig.telegramURL)
                    }
                    HoneyButton(title: BeeStrings.t("🆘 Support"), filled: false) {
                        open(BeeConfig.supportURL)
                    }
                    if !license.isConfigured {
                        Text(BeeStrings.t("This build was made without a license server"))
                            .font(.system(size: 12))
                            .foregroundColor(palette.muted)
                    }
                }
                .padding(24)
            }
        }
    }

    private var picker: some View {
        HStack(spacing: 8) {
            segment(BeeStrings.t("Activation code"), selected: tab == .code) { tab = .code }
            segment(BeeStrings.t("Account"), selected: tab == .account) { tab = .account }
        }
        .padding(6)
        .background(RoundedRectangle(cornerRadius: 14).fill(palette.surface))
    }

    private func segment(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 14, weight: selected ? .bold : .regular))
                .foregroundColor(selected ? palette.canvas : palette.muted)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 9)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(selected ? palette.primary : Color.clear)
                )
        }
        .buttonStyle(.plain)
    }

    private var inputCard: some View {
        VStack(spacing: 12) {
            if tab == .code {
                TextField("BEE-XXXX-XXXX-XXXX", text: $code)
                    .font(.system(size: 17, weight: .semibold, design: .monospaced))
                    .foregroundColor(palette.ink)
                    .textInputAutocapitalization(.characters)
                    .disableAutocorrection(true)
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 12).fill(palette.track))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.divider, lineWidth: 1))
            } else {
                TextField(BeeStrings.t("Username"), text: $username)
                    .font(.system(size: 16))
                    .foregroundColor(palette.ink)
                    .disableAutocorrection(true)
                    .textInputAutocapitalization(.never)
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 12).fill(palette.track))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.divider, lineWidth: 1))
                SecureField(BeeStrings.t("Password"), text: $password)
                    .font(.system(size: 16))
                    .foregroundColor(palette.ink)
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 12).fill(palette.track))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.divider, lineWidth: 1))
            }
        }
        .honeyCard()
    }

    @MainActor
    private func submit() async {
        error = nil
        busy = true
        defer { busy = false }
        let failure: String?
        if tab == .code {
            failure = await license.activate(code: code)
        } else {
            failure = await license.login(username: username, password: password)
        }
        if let failure {
            error = failure
        }
    }

    private func open(_ url: String) {
        guard let u = URL(string: url) else { return }
        UIApplication.shared.open(u)
    }
}
