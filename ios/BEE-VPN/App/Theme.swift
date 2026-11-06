import SwiftUI
import UIKit

// MARK: - Honey palette (mirrors SubscriptionInfoActivity / LicenseGateActivity)

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0)
    }
}

enum Honey {
    static let canvas = Color(hex: 0x04070B)
    static let surface = Color(hex: 0x0B1116)
    static let divider = Color(hex: 0x1C2429)
    static let ink = Color(hex: 0xEAF6F3)
    static let muted = Color(hex: 0x9DB0B5)
    static let primary = Color(hex: 0xFFC107)
    static let danger = Color(hex: 0xFF6B7F)
    static let ok = Color(hex: 0x59D98E)
    static let track = Color(hex: 0x141C22)
}

// MARK: - Persian digits & dates (ported 1:1 from the Android app)

func faDigits(_ s: String) -> String {
    let map: [Character: Character] = [
        "0": "۰", "1": "۱", "2": "۲", "3": "۳", "4": "۴",
        "5": "۵", "6": "۶", "7": "۷", "8": "۸", "9": "۹",
    ]
    return String(s.map { map[$0] ?? $0 })
}

/// Iran has been UTC+3:30 year-round since 2022 — the same offset the license
/// server uses, so app and bot always print the same day.
let tehranOffset: TimeInterval = 12_600

private func utcDate(_ epochSec: Int64) -> (Int, Int, Int) {
    let date = Date(timeIntervalSince1970: TimeInterval(epochSec) + tehranOffset)
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "UTC")!
    let c = calendar.dateComponents([.year, .month, .day], from: date)
    return (c.year ?? 1970, c.month ?? 1, c.day ?? 1)
}

func gregorianString(_ epochSec: Int64) -> String {
    let (y, m, d) = utcDate(epochSec)
    return faDigits(String(format: "%04d-%02d-%02d", y, m, d))
}

/// Gregorian → Jalali, the classic jdf.scr.ir algorithm — exactly the code
/// SubscriptionInfoActivity.kt runs, so both apps print the same date.
func jalaliString(_ epochSec: Int64) -> String {
    var gy = utcDate(epochSec)
    let gDm = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334]
    var jy = gy.0 <= 1600 ? 0 : 979
    gy.0 -= gy.0 <= 1600 ? 621 : 1600
    let gy2 = gy.1 > 2 ? gy.0 + 1 : gy.0
    var days = 365 * gy.0 + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400
    days += -80 + gDm[gy.1 - 1] + gy.2
    jy += 33 * (days / 12053)
    days %= 12053
    jy += 4 * (days / 1461)
    days %= 1461
    jy += (days - 1) / 365
    if days > 365 { days = (days - 1) % 365 }
    let jm = days < 186 ? 1 + days / 31 : 7 + (days - 186) / 30
    let jd = 1 + (days < 186 ? days % 31 : (days - 186) % 30)
    return faDigits(String(format: "%04d/%02d/%02d", jy, jm, jd))
}

// MARK: - Shared components

struct HoneyCard: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 14).fill(Honey.surface))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Honey.divider, lineWidth: 1))
    }
}

extension View {
    func honeyCard() -> some View { modifier(HoneyCard()) }
}

struct InfoRow: View {
    let title: String
    let value: String
    var valueColor: Color = Honey.ink

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.system(size: 12))
                .foregroundColor(Honey.muted)
            Text(value)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(valueColor)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .background(RoundedRectangle(cornerRadius: 12).fill(Honey.surface))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Honey.divider, lineWidth: 1))
    }
}

struct HoneyButton: View {
    let title: String
    var filled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(filled ? Honey.canvas : Honey.primary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
                .background(
                    RoundedRectangle(cornerRadius: 14)
                        .fill(filled ? Honey.primary : Honey.surface)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 14)
                        .stroke(filled ? Color.clear : Honey.primary, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Monospace multi-line editor (iOS 15-safe, transparent background)

struct MonoEditor: UIViewRepresentable {
    @Binding var text: String

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.backgroundColor = .clear
        view.textColor = UIColor(red: 0.92, green: 0.96, blue: 0.95, alpha: 1) // HONEY_INK
        view.autocapitalizationType = .none
        view.autocorrectionType = .no
        view.smartQuotesType = .no
        view.keyboardType = .asciiCapable
        view.delegate = context.coordinator
        return view
    }

    func updateUIView(_ view: UITextView, context: Context) {
        if view.text != text {
            view.text = text
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UITextViewDelegate {
        let parent: MonoEditor
        init(_ parent: MonoEditor) { self.parent = parent }

        func textViewDidChange(_ textView: UITextView) {
            parent.text = textView.text
        }
    }
}
