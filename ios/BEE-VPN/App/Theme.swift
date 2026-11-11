import SwiftUI
import UIKit

// MARK: - Honey palettes (dark mirrors SubscriptionInfoActivity / LicenseGateActivity)

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0)
    }
}

/// The dark honey palette — the original BEE-VPN look.
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

/// Semantic palette resolved from the Appearance section (system/light/dark —
/// the same three options the Android Appearance screen offers).
struct Palette {
    let canvas: Color
    let surface: Color
    let divider: Color
    let ink: Color
    let muted: Color
    let primary: Color
    let danger: Color
    let ok: Color
    let track: Color

    static let dark = Palette(
        canvas: Honey.canvas, surface: Honey.surface, divider: Honey.divider,
        ink: Honey.ink, muted: Honey.muted, primary: Honey.primary,
        danger: Honey.danger, ok: Honey.ok, track: Honey.track)

    static let light = Palette(
        canvas: Color(hex: 0xF8F4EA),
        surface: Color(hex: 0xFFFFFF),
        divider: Color(hex: 0xE3DAC8),
        ink: Color(hex: 0x221A0D),
        muted: Color(hex: 0x6E6354),
        primary: Color(hex: 0xE0A400),
        danger: Color(hex: 0xC93A5C),
        ok: Color(hex: 0x1F9D5B),
        track: Color(hex: 0xEEE7D7))
}

private struct BeePaletteKey: EnvironmentKey {
    static let defaultValue: Palette = .dark
}

extension EnvironmentValues {
    var beePalette: Palette {
        get { self[BeePaletteKey.self] }
        set { self[BeePaletteKey.self] = newValue }
    }
}

/// Theme + language chooser state. Theme follows the Android Appearance
/// section ("Follow system" / روشن / تاریک); language is en/fa with the
/// one-time picker, exactly like the Android language flow.
@MainActor
final class AppStyle: ObservableObject {
    static let shared = AppStyle()

    @Published var theme: String {
        didSet { UserDefaults.standard.set(theme, forKey: "app_theme") }
    }
    @Published var language: String {
        didSet { BeeStrings.language = language }
    }

    private init() {
        theme = UserDefaults.standard.string(forKey: "app_theme") ?? "dark"
        language = BeeStrings.language
    }

    var resolvedScheme: ColorScheme? {
        switch theme {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

    var themeLabel: String {
        switch theme {
        case "light": return BeeStrings.t("Light — theme")
        case "dark": return BeeStrings.t("Dark")
        default: return BeeStrings.t("Follow system")
        }
    }
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

// MARK: - Shared components (palette-aware)

struct HoneyCard: ViewModifier {
    @Environment(\.beePalette) private var palette

    func body(content: Content) -> some View {
        content
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 14).fill(palette.surface))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(palette.divider, lineWidth: 1))
    }
}

extension View {
    func honeyCard() -> some View { modifier(HoneyCard()) }
}

struct InfoRow: View {
    @Environment(\.beePalette) private var palette
    let title: String
    let value: String
    var valueColor: Color?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.system(size: 12))
                .foregroundColor(palette.muted)
            Text(value)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(valueColor ?? palette.ink)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .background(RoundedRectangle(cornerRadius: 12).fill(palette.surface))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.divider, lineWidth: 1))
    }
}

struct HoneyButton: View {
    @Environment(\.beePalette) private var palette
    let title: String
    var filled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(filled ? palette.canvas : palette.primary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
                .background(
                    RoundedRectangle(cornerRadius: 14)
                        .fill(filled ? palette.primary : palette.surface)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 14)
                        .stroke(filled ? Color.clear : palette.primary, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Monospace multi-line editor (iOS 15-safe, transparent background)

struct MonoEditor: UIViewRepresentable {
    @Binding var text: String
    @Environment(\.beePalette) private var palette

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.backgroundColor = .clear
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
        view.textColor = UIColor(palette.ink)
        view.font = .monospacedSystemFont(ofSize: 13, weight: .regular)
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
