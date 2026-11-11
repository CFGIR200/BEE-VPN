import SwiftUI

/// App entry: the license gate in front, the three-tab console behind it.
/// The opening overlay and the one-time language picker follow the Android
/// flow (showOpeningOverlay / showLanguagePickerOnce); the theme follows the
/// Appearance section (system/light/dark).
@main
struct BeeApp: App {
    @ObservedObject private var license = LicenseManager.shared
    @ObservedObject private var style = AppStyle.shared

    var body: some Scene {
        WindowGroup {
            RootView(license: license)
                .preferredColorScheme(style.resolvedScheme)
                .environmentObject(style)
        }
    }
}

struct RootView: View {
    @ObservedObject var license: LicenseManager
    @EnvironmentObject var style: AppStyle
    @Environment(\.colorScheme) private var systemScheme

    @State private var showOpening = true
    @AppStorage("language_chosen") private var languageChosen = false

    var body: some View {
        ZStack {
            Group {
                if license.isConfigured, case .locked = license.state {
                    GateView(notice: lockReason)
                } else {
                    mainTabs
                }
            }
            .environment(\.beePalette, activePalette)
            .environment(\.layoutDirection, BeeStrings.usesPersian ? .rightToLeft : .leftToRight)

            if showOpening {
                OpeningOverlay()
            }
        }
        .onAppear {
            license.refreshState()
            Task {
                try? await Task.sleep(nanoseconds: 1_600_000_000)
                withAnimation(.easeOut(duration: 0.25)) { showOpening = false }
            }
        }
        .sheet(isPresented: languageSheetBinding) {
            LanguagePickerView()
                .environment(\.beePalette, activePalette)
        }
    }

    private var languageSheetBinding: Binding<Bool> {
        Binding(
            get: { !languageChosen && !showOpening },
            set: { shown in
                if !shown {
                    languageChosen = true
                    UserDefaults.standard.set(true, forKey: "language_chosen")
                }
            })
    }

    private var activePalette: Palette {
        switch style.theme {
        case "light": return .light
        case "dark": return .dark
        default: return systemScheme == .dark ? .dark : .light
        }
    }

    private var lockReason: String? {
        if case .locked(let reason) = license.state { return reason }
        return nil
    }

    private var mainTabs: some View {
        TabView {
            HomeView()
                .tabItem { Label(BeeStrings.t("Home"), systemImage: "bolt.horizontal.circle") }
            SubscriptionView()
                .tabItem { Label(BeeStrings.t("Subscription"), systemImage: "creditcard") }
            AdvancedView()
                .tabItem { Label(BeeStrings.t("Advanced"), systemImage: "gearshape") }
        }
        .tint(activePalette.primary)
    }
}

/// The splash the Android app shows on launch: honey canvas, the bee and a
/// signal trace, gone after ~1.6 s.
struct OpeningOverlay: View {
    @Environment(\.beePalette) private var palette

    var body: some View {
        ZStack {
            palette.canvas.ignoresSafeArea()
            VStack(spacing: 18) {
                Text("🐝")
                    .font(.system(size: 72))
                Text("BEE-VPN")
                    .font(.system(size: 28, weight: .heavy, design: .rounded))
                    .foregroundColor(palette.ink)
                ProgressView()
                    .tint(palette.primary)
                Text(BeeStrings.t("SECURED BY BEE-VPN"))
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(palette.muted)
                    .padding(.top, 26)
            }
        }
        .transition(.opacity)
        .zIndex(10)
    }
}

/// The one-time language picker — same copy as Android's
/// showLanguagePickerOnce, English and فارسی.
struct LanguagePickerView: View {
    @Environment(\.beePalette) private var palette
    @EnvironmentObject var style: AppStyle
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 22) {
            Text("🐝").font(.system(size: 54))
            Text(BeeStrings.t("Choose the app's language. You can change it later in Settings."))
                .font(.system(size: 16, weight: .medium))
                .foregroundColor(palette.ink)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 28)
            HStack(spacing: 14) {
                languageButton("English") {
                    style.language = "en"
                    dismiss()
                }
                languageButton("فارسی") {
                    style.language = "fa"
                    dismiss()
                }
            }
            .padding(.horizontal, 28)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(palette.canvas.ignoresSafeArea())
    }

    private func languageButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(palette.primary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(RoundedRectangle(cornerRadius: 14).fill(palette.surface))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(palette.primary, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}
