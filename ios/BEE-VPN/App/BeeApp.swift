import SwiftUI

/// App entry: the license gate in front, the three-tab console behind it.
/// Everything is Persian-first with RTL layout, matching the Android app.
@main
struct BeeApp: App {
    @ObservedObject private var license = LicenseManager.shared

    var body: some Scene {
        WindowGroup {
            RootView(license: license)
                .environment(\.layoutDirection, .rightToLeft)
                .preferredColorScheme(.dark)
        }
    }
}

struct RootView: View {
    @ObservedObject var license: LicenseManager

    var body: some View {
        Group {
            if license.isConfigured, case .locked = license.state {
                GateView(notice: lockReason)
            } else {
                mainTabs
            }
        }
        .onAppear { license.refreshState() }
    }

    private var lockReason: String? {
        if case .locked(let reason) = license.state { return reason }
        return nil
    }

    private var mainTabs: some View {
        TabView {
            HomeView()
                .tabItem { Label("اتصال", systemImage: "bolt.horizontal.circle") }
            SubscriptionView()
                .tabItem { Label("اشتراک", systemImage: "creditcard") }
            AdvancedView()
                .tabItem { Label("پیشرفته", systemImage: "gearshape") }
        }
        .tint(Honey.primary)
    }
}
