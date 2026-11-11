import SwiftUI
import UIKit

/// The in-app subscription profile — the exact mirror of Android's
/// SubscriptionInfoActivity: status, days left (Persian digits), plan, Jalali
/// + Gregorian expiry, term progress, device binding, last verification.
/// Deliberately OFFLINE: it reads the Ed25519-verified token already on the
/// device, so the profile can never disagree with the gate.
struct SubscriptionView: View {
    @Environment(\.beePalette) private var palette
    @ObservedObject private var license = LicenseManager.shared

    var body: some View {
        ZStack {
            palette.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    Text("🐝")
                        .font(.system(size: 40))
                    Text("BEE-VPN")
                        .font(.system(size: 24, weight: .heavy))
                        .foregroundColor(palette.primary)
                    Text(BeeStrings.t("Subscription"))
                        .font(.system(size: 15))
                        .foregroundColor(palette.ink)

                    if !info.configured {
                        Text(BeeStrings.t("This build was made without a license server"))
                            .font(.system(size: 14.5))
                            .foregroundColor(palette.muted)
                            .padding(.top, 24)
                    } else {
                        hero
                        rows
                        actions
                        Text(BeeStrings.t("Every subscription is bound to exactly one device. Changed your phone? Ask support for a reset."))
                            .font(.system(size: 12))
                            .foregroundColor(palette.muted)
                            .multilineTextAlignment(.center)
                            .padding(.top, 18)
                    }
                }
                .padding(24)
            }
        }
        .onAppear { license.verifyInBackground() }
    }

    private var info: LicenseManager.SubscriptionInfo { license.subscriptionInfo() }

    /// Expired vs missing: decodePayload reads the (already-signed-at-issue)
    /// payload for display only — the gate itself keeps trusting verifyToken.
    private var expiredToken: Bool {
        guard !info.hasToken, let token = license.storedToken else { return false }
        guard let payload = BeeCrypto.decodePayload(token) else { return false }
        return payload.expiry <= nowSec()
    }

    private var hero: some View {
        VStack(spacing: 8) {
            if info.hasToken {
                let daysLeft = Int((info.expiryEpochSec - nowSec()) / 86400) + 1
                Text("✅ " + BeeStrings.t("Subscription active"))
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(palette.ok)
                Text(BeeStrings.tf("%d days left", faDigits("\(daysLeft)")))
                    .font(.system(size: 15))
                    .foregroundColor(palette.ink)
                progressBar
            } else if expiredToken {
                Text("❌ " + BeeStrings.t("Subscription expired"))
                    .font(.system(size: 16.5, weight: .semibold))
                    .foregroundColor(palette.danger)
                    .multilineTextAlignment(.center)
            } else {
                Text("⚠️ " + BeeStrings.t("No active subscription on this device"))
                    .font(.system(size: 16.5, weight: .semibold))
                    .foregroundColor(palette.danger)
                    .multilineTextAlignment(.center)
            }
        }
        .honeyCard()
        .padding(.top, 4)
    }

    @ViewBuilder
    private var progressBar: some View {
        if info.expiryEpochSec > 0, info.activatedAtEpochSec > 0, info.expiryEpochSec > info.activatedAtEpochSec {
            let total = Double(info.expiryEpochSec - info.activatedAtEpochSec)
            let elapsed = min(max(Double(nowSec() - info.activatedAtEpochSec), 0), total)
            let fraction = max(CGFloat(elapsed / total), 0.02)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 4).fill(palette.track)
                    RoundedRectangle(cornerRadius: 4).fill(palette.primary)
                        .frame(width: geo.size.width * fraction)
                }
            }
            .frame(height: 8)
            .padding(.top, 12)
        }
    }

    private var rows: some View {
        VStack(spacing: 8) {
            InfoRow(title: BeeStrings.t("Status"),
                    value: info.hasToken ? BeeStrings.t("Active") : (expiredToken ? BeeStrings.t("Expired") : "—"),
                    valueColor: info.hasToken ? palette.ok : (expiredToken ? palette.danger : palette.muted))
            InfoRow(title: BeeStrings.t("Plan"), value: planLabel)
            InfoRow(title: BeeStrings.t("Expiry date"), value: expiryLine)
            InfoRow(title: BeeStrings.t("Term start"), value: startLine)
            InfoRow(title: BeeStrings.t("Type"), value: kindLabel)
            InfoRow(title: BeeStrings.t("ID"), value: info.subject.isEmpty ? "—" : info.subject)
            InfoRow(title: BeeStrings.t("Device"), value: String(info.installId.prefix(12)) + "…")
            InfoRow(title: BeeStrings.t("Last verified"), value: checkLine)
        }
        .padding(.top, 4)
    }

    private var actions: some View {
        VStack(spacing: 10) {
            HoneyButton(title: BeeStrings.t("🛍 Buy subscription"), filled: true) {
                open(BeeConfig.telegramURL)
            }
            HoneyButton(title: BeeStrings.t("🆘 Support"), filled: false) {
                open(BeeConfig.supportURL)
            }
        }
        .padding(.top, 6)
    }

    private var planLabel: String {
        switch info.plan {
        case "trial": return BeeStrings.t("Trial (1 day)")
        case "m1": return BeeStrings.t("1 month")
        case "m3": return BeeStrings.t("3 months")
        case "m6": return BeeStrings.t("6 months")
        case "m12": return BeeStrings.t("12 months")
        default: return "—"
        }
    }

    private var kindLabel: String {
        switch info.kind {
        case "code": return BeeStrings.t("Activation code")
        case "account": return BeeStrings.t("Account")
        default: return "—"
        }
    }

    private var expiryLine: String {
        info.expiryEpochSec > 0
            ? "\(jalaliString(info.expiryEpochSec))  (\(gregorianString(info.expiryEpochSec)))"
            : BeeStrings.t("Clock starts at first activation")
    }

    private var startLine: String {
        info.activatedAtEpochSec > 0
            ? jalaliString(info.activatedAtEpochSec)
            : BeeStrings.t("Clock starts at first activation")
    }

    private var checkLine: String {
        info.lastCheckEpochSec > 0 ? jalaliString(info.lastCheckEpochSec) : BeeStrings.t("Never checked")
    }

    private func nowSec() -> Int64 { Int64(Date().timeIntervalSince1970) }

    private func open(_ url: String) {
        guard let u = URL(string: url) else { return }
        UIApplication.shared.open(u)
    }
}
