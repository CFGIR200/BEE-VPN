import SwiftUI
import UIKit

/// The activation gate — two tabs (activation code / account), the same flow
/// and the same honey palette as LicenseGateActivity on Android.
struct GateView: View {
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
            Honey.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 18) {
                    Text("🐝")
                        .font(.system(size: 44))
                    Text("BEE-VPN")
                        .font(.system(size: 28, weight: .heavy))
                        .foregroundColor(Honey.primary)
                    Text("اشتراک را فعال کن تا استفاده کنی")
                        .font(.system(size: 15))
                        .foregroundColor(Honey.ink)

                    picker
                    if let notice {
                        Text(notice)
                            .font(.system(size: 13.5, weight: .semibold))
                            .foregroundColor(Honey.danger)
                            .multilineTextAlignment(.center)
                    }
                    inputCard
                    if let error {
                        Text(error)
                            .font(.system(size: 13.5))
                            .foregroundColor(Honey.danger)
                            .multilineTextAlignment(.center)
                    }
                    HoneyButton(title: busy ? "در حال فعال‌سازی…" : "فعال‌سازی", filled: true) {
                        Task { await submit() }
                    }
                    .disabled(busy)

                    HoneyButton(title: "🛍 خرید اشتراک", filled: false) {
                        open(BeeConfig.telegramURL)
                    }
                    HoneyButton(title: "🆘 پشتیبانی", filled: false) {
                        open(BeeConfig.supportURL)
                    }
                    if !license.isConfigured {
                        Text("این نسخه بدون سرور لایسنس ساخته شده است")
                            .font(.system(size: 12))
                            .foregroundColor(Honey.muted)
                    }
                }
                .padding(24)
            }
        }
        .preferredColorScheme(.dark)
    }

    private var picker: some View {
        HStack(spacing: 8) {
            segment("کد فعال‌سازی", selected: tab == .code) { tab = .code }
            segment("حساب کاربری", selected: tab == .account) { tab = .account }
        }
        .padding(6)
        .background(RoundedRectangle(cornerRadius: 14).fill(Honey.surface))
    }

    private func segment(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 14, weight: selected ? .bold : .regular))
                .foregroundColor(selected ? Honey.canvas : Honey.muted)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 9)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(selected ? Honey.primary : Color.clear)
                )
        }
        .buttonStyle(.plain)
    }

    private var inputCard: some View {
        VStack(spacing: 12) {
            if tab == .code {
                TextField("BEE-XXXX-XXXX-XXXX", text: $code)
                    .font(.system(size: 17, weight: .semibold, design: .monospaced))
                    .foregroundColor(Honey.ink)
                    .textInputAutocapitalization(.characters)
                    .disableAutocorrection(true)
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Honey.track))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Honey.divider, lineWidth: 1))
            } else {
                TextField("نام کاربری", text: $username)
                    .font(.system(size: 16))
                    .foregroundColor(Honey.ink)
                    .disableAutocorrection(true)
                    .textInputAutocapitalization(.never)
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Honey.track))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Honey.divider, lineWidth: 1))
                SecureField("رمز عبور", text: $password)
                    .font(.system(size: 16))
                    .foregroundColor(Honey.ink)
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Honey.track))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Honey.divider, lineWidth: 1))
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
