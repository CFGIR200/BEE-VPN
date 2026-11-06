import SwiftUI
import UIKit

/// The in-app subscription profile — the exact mirror of Android's
/// SubscriptionInfoActivity: status, days left (Persian digits), plan, Jalali
/// + Gregorian expiry, term progress, device binding, last verification.
/// Deliberately OFFLINE: it reads the Ed25519-verified token already on the
/// device, so the profile can never disagree with the gate.
struct SubscriptionView: View {
    @ObservedObject private var license = LicenseManager.shared

    var body: some View {
        ZStack {
            Honey.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    Text("🐝")
                        .font(.system(size: 40))
                    Text("BEE-VPN")
                        .font(.system(size: 24, weight: .heavy))
                        .foregroundColor(Honey.primary)
                    Text("اشتراک")
                        .font(.system(size: 15))
                        .foregroundColor(Honey.ink)

                    if !info.configured {
                        Text("این نسخه بدون سرور لایسنس ساخته شده است")
                            .font(.system(size: 14.5))
                            .foregroundColor(Honey.muted)
                            .padding(.top, 24)
                    } else {
                        hero
                        rows
                        actions
                        Text("هر اشتراک فقط به یک دستگاه وصل است. گوشی را عوض کردی؟ برای انتقال از پشتیبانی بخواه.")
                            .font(.system(size: 12))
                            .foregroundColor(Honey.muted)
                            .multilineTextAlignment(.center)
                            .padding(.top, 18)
                    }
                }
                .padding(24)
            }
        }
        .preferredColorScheme(.dark)
        .onAppear { license.verifyInBackground() }
    }

    private var info: LicenseManager.SubscriptionInfo { license.subscriptionInfo() }

    private var hero: some View {
        VStack(spacing: 8) {
            if info.hasToken {
                let daysLeft = Int((info.expiryEpochSec - nowSec()) / 86400) + 1
                Text("✅ اشتراک فعال")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(Honey.ok)
                Text(faDigits("\(daysLeft)") + " روز باقی مانده")
                    .font(.system(size: 15))
                    .foregroundColor(Honey.ink)
                progressBar
            } else {
                Text("⚠️ اشتراک فعالی روی این دستگاه نیست")
                    .font(.system(size: 16.5, weight: .semibold))
                    .foregroundColor(Honey.danger)
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
                    RoundedRectangle(cornerRadius: 4).fill(Honey.track)
                    RoundedRectangle(cornerRadius: 4).fill(Honey.primary)
                        .frame(width: geo.size.width * fraction)
                }
            }
            .frame(height: 8)
            .padding(.top, 12)
        }
    }

    private var rows: some View {
        VStack(spacing: 8) {
            InfoRow(title: "وضعیت", value: info.hasToken ? "فعال" : "—",
                    valueColor: info.hasToken ? Honey.ok : Honey.muted)
            InfoRow(title: "پلن", value: planLabel)
            InfoRow(title: "تاریخ انقضا", value: expiryLine)
            InfoRow(title: "شروع دوره", value: startLine)
            InfoRow(title: "نوع", value: kindLabel)
            InfoRow(title: "شناسه", value: info.subject.isEmpty ? "—" : info.subject)
            InfoRow(title: "دستگاه", value: String(info.installId.prefix(12)) + "…")
            InfoRow(title: "آخرین بررسی", value: checkLine)
        }
        .padding(.top, 4)
    }

    private var actions: some View {
        VStack(spacing: 10) {
            HoneyButton(title: "🛍 خرید اشتراک", filled: true) {
                open(BeeConfig.telegramURL)
            }
            HoneyButton(title: "🆘 پشتیبانی", filled: false) {
                open(BeeConfig.supportURL)
            }
        }
        .padding(.top, 6)
    }

    private var planLabel: String {
        switch info.plan {
        case "trial": return "آزمایشی (۱ روز)"
        case "m1": return "۱ ماهه"
        case "m3": return "۳ ماهه"
        case "m6": return "۶ ماهه"
        case "m12": return "۱۲ ماهه"
        default: return "—"
        }
    }

    private var kindLabel: String {
        switch info.kind {
        case "code": return "کد فعال‌سازی"
        case "account": return "حساب کاربری"
        default: return "—"
        }
    }

    private var expiryLine: String {
        info.expiryEpochSec > 0
            ? "\(jalaliString(info.expiryEpochSec))  (\(gregorianString(info.expiryEpochSec)))"
            : "هنوز شروع نشده — ساعت از اولین فعال‌سازی می‌چرخد"
    }

    private var startLine: String {
        info.activatedAtEpochSec > 0
            ? jalaliString(info.activatedAtEpochSec)
            : "هنوز شروع نشده — ساعت از اولین فعال‌سازی می‌چرخد"
    }

    private var checkLine: String {
        info.lastCheckEpochSec > 0 ? jalaliString(info.lastCheckEpochSec) : "بررسی نشده"
    }

    private func nowSec() -> Int64 { Int64(Date().timeIntervalSince1970) }

    private func open(_ url: String) {
        guard let u = URL(string: url) else { return }
        UIApplication.shared.open(u)
    }
}
