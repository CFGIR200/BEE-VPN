import SwiftUI

/// Settings — the Android openSettingsScreen sections, in order: PROFILE,
/// SUBSCRIPTION, PROTECTION, ROUTING & DATA, CONNECTION, APPEARANCE, BACKUP,
/// ABOUT. Rows that belong to the Android core engine stay visible but
/// disabled, with an honest reason instead of a silent no-op.
struct SettingsView: View {
    @Environment(\.beePalette) private var palette
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var settings = AppSettings.shared
    @ObservedObject private var style = AppStyle.shared

    @State private var showProfileSheet = false
    @State private var showSubscription = false
    @State private var showTraffic = false
    @State private var showMTU = false
    @State private var showWarpSetting = false
    @State private var showControls = false
    @State private var showLogs = false
    @State private var showBackup = false
    @State private var showRestore = false
    @State private var showResetConfirm = false
    @State private var updateMessage: String?

    var body: some View {
        NavigationStack {
            ZStack {
                palette.canvas.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 4) {
                        profileSection
                        subscriptionSection
                        protectionSection
                        routingSection
                        connectionSection
                        appearanceSection
                        backupSection
                        aboutSection
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                }
            }
            .navigationTitle(BeeStrings.t("Settings"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(BeeStrings.t("Done")) { dismiss() }
                        .foregroundColor(palette.primary)
                }
            }
            .sheet(isPresented: $showProfileSheet) { ProfileSheet() }
            .sheet(isPresented: $showSubscription) { SubscriptionSheet() }
            .sheet(isPresented: $showTraffic) { TrafficMonitorView() }
            .sheet(isPresented: $showMTU) { MtuSheet() }
            .sheet(isPresented: $showWarpSetting) { WarpSettingView() }
            .sheet(isPresented: $showControls) { TunnelControlsView() }
            .sheet(isPresented: $showLogs) { LogsView() }
            .sheet(isPresented: $showBackup) { BackupSheet(mode: .export) }
            .sheet(isPresented: $showRestore) { BackupSheet(mode: .import) }
            .alert(BeeStrings.t("Reset to defaults"), isPresented: $showResetConfirm) {
                Button(BeeStrings.t("Cancel"), role: .cancel) {}
                Button(BeeStrings.t("Reset to defaults"), role: .destructive) {
                    settings.resetToDefaults()
                }
            } message: {
                Text(BeeStrings.t("Forget every setting"))
            }
        }
    }

    // ------------------------------------------------------------ section frame

    private func section(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(BeeStrings.t(title))
                .font(.system(size: 11, weight: .heavy))
                .foregroundColor(palette.muted)
                .padding(.top, 12)
            content()
        }
    }

    private func row(_ title: String,
                     summary: String? = nil,
                     value: String? = nil,
                     enabled: Bool = true,
                     action: (() -> Void)? = nil) -> some View {
        Button {
            action?()
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(enabled ? palette.ink : palette.muted)
                    if let summary {
                        Text(summary)
                            .font(.system(size: 11))
                            .foregroundColor(palette.muted.opacity(0.85))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer()
                if let value {
                    Text(value)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(palette.muted)
                }
                if action != nil {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(palette.muted.opacity(0.6))
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 12).fill(palette.surface))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.divider, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(!enabled || action == nil)
    }

    private func toggleRow(_ title: String,
                           summary: String,
                           binding: Binding<Bool>,
                           enabled: Bool = true,
                           disabledSummary: String? = nil) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(enabled ? palette.ink : palette.muted)
                Text(enabled ? summary : (disabledSummary ?? summary))
                    .font(.system(size: 11))
                    .foregroundColor(palette.muted.opacity(0.85))
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            Toggle("", isOn: binding)
                .labelsHidden()
                .tint(palette.primary)
                .disabled(!enabled)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 12).fill(palette.surface))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.divider, lineWidth: 1))
    }

    // ------------------------------------------------------------ sections

    private var profileSection: some View {
        section("PROFILE") {
            row(BeeStrings.t("Profile"),
                summary: BeeStrings.t("Four independent sets of settings. Each keeps its own protocol, transport, kill switch and shaping. Backup, restore and reset cover all four."),
                value: faDigits("\(settings.profile)"),
                action: { showProfileSheet = true })
        }
    }

    private var subscriptionSection: some View {
        section("SUBSCRIPTION") {
            row(BeeStrings.t("Subscription"),
                summary: LicenseManager.shared.subscriptionSummary,
                action: { showSubscription = true })
        }
    }

    private var protectionSection: some View {
        section("PROTECTION") {
            toggleRow(BeeStrings.t("Kill switch"),
                      summary: BeeStrings.t("Block all traffic if the tunnel drops"),
                      binding: $settings.killSwitch,
                      enabled: false,
                      disabledSummary: BeeStrings.t("Kill switch is not available on iOS yet"))
            toggleRow(BeeStrings.t("Auto reconnect"),
                      summary: BeeStrings.t("Reconnect automatically if the tunnel drops"),
                      binding: $settings.autoReconnect)
        }
    }

    private var routingSection: some View {
        section("ROUTING & DATA") {
            row(BeeStrings.t("Traffic monitor"),
                action: { showTraffic = true })
            toggleRow(BeeStrings.t("Bypass Iran"),
                      summary: BeeStrings.t("Iranian sites and apps go direct, outside the tunnel"),
                      binding: $settings.bypassIran)
            toggleRow(BeeStrings.t("Local network access"),
                      summary: BeeStrings.t("Reach printers, NAS and your router while connected"),
                      binding: $settings.lanAccess)
            row(BeeStrings.t("Split tunneling"),
                summary: BeeStrings.t("Split tunneling needs a managed entitlement"),
                enabled: false)
        }
    }

    private var connectionSection: some View {
        section("CONNECTION") {
            row(BeeStrings.t("Tunnel type"),
                summary: BeeStrings.t("SOCKS mode is an Android tunnel type"),
                value: BeeStrings.t("VPN (whole device)"),
                enabled: false)
            row(BeeStrings.t("MTU"),
                value: mtuValue,
                action: { showMTU = true })
            row(BeeStrings.t("Warp Setting"),
                action: { showWarpSetting = true })
            row(BeeStrings.t("Tunnel controls"),
                summary: BeeStrings.t("Shaping · Anti-DPI"),
                action: { showControls = true })
            row(BeeStrings.t("Log"),
                action: { showLogs = true })
            row(BeeStrings.t("Scan Mode"),
                summary: BeeStrings.t("The engine scanner is part of the Android core"),
                enabled: false)
        }
    }

    private var appearanceSection: some View {
        section("APPEARANCE") {
            row(BeeStrings.t("Theme"),
                value: style.themeLabel,
                action: { cycleTheme() })
            row(BeeStrings.t("Language"),
                value: style.language == "fa" ? "فارسی" : "English",
                action: { style.language = style.language == "fa" ? "en" : "fa" })
        }
    }

    private var backupSection: some View {
        section("BACKUP") {
            row(BeeStrings.t("Back up settings"),
                action: { showBackup = true })
            row(BeeStrings.t("Restore settings"),
                action: { showRestore = true })
            row(BeeStrings.t("Reset to defaults"),
                action: { showResetConfirm = true })
        }
    }

    private var aboutSection: some View {
        section("ABOUT") {
            row(BeeStrings.t("Check for updates"),
                summary: updateMessage,
                action: { Task { await checkForUpdates() } })
            linkRow(BeeStrings.t("Source on GitHub"), url: "https://github.com/CFGIR200/BEE-VPN")
            linkRow(BeeStrings.t("Telegram Channel"), url: BeeConfig.telegramURL)
            linkRow(BeeStrings.t("Contact Admin"), url: BeeConfig.supportURL)
            row(BeeStrings.t("Version"),
                value: "BEE-VPN " + faDigits(AppVersion.short),
                enabled: false)
            Text(BeeStrings.t("Based on BEEHIVE-GUARD - AGPL-3.0"))
                .font(.system(size: 10))
                .foregroundColor(palette.muted.opacity(0.7))
                .padding(.top, 4)
        }
    }

    private func linkRow(_ title: String, url: String) -> some View {
        Link(destination: URL(string: url)!) {
            HStack {
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(palette.ink)
                Spacer()
                Image(systemName: "arrow.up.right")
                    .font(.system(size: 12))
                    .foregroundColor(palette.muted)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 12).fill(palette.surface))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.divider, lineWidth: 1))
        }
    }

    private var mtuValue: String {
        let v = settings.mtu.trimmingCharacters(in: .whitespaces)
        return v.isEmpty ? faDigits("1280") : faDigits(v)
    }

    private func cycleTheme() {
        let order = ["system", "light", "dark"]
        let idx = order.firstIndex(of: style.theme) ?? 0
        style.theme = order[(idx + 1) % order.count]
    }

    // ------------------------------------------------------------ updates

    private func checkForUpdates() async {
        updateMessage = BeeStrings.t("Working on it…")
        do {
            let (data, _) = try await URLSession.shared.data(
                from: URL(string: "https://api.github.com/repos/CFGIR200/BEE-VPN/releases/latest")!)
            let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            let tag = (obj?["tag_name"] as? String) ?? ""
            let latest = tag.hasPrefix("v") ? String(tag.dropFirst()) : tag
            if latest.isEmpty { throw URLError(.badServerResponse) }
            if latest == AppVersion.short {
                updateMessage = BeeStrings.t("You're up to date")
            } else {
                updateMessage = BeeStrings.tf("BEE-VPN %s has been released. You are on %s.",
                                              faDigits(latest), faDigits(AppVersion.short))
            }
        } catch {
            updateMessage = BeeStrings.t("Update check failed")
        }
    }
}

// ------------------------------------------------------------ sheets

struct ProfileSheet: View {
    @Environment(\.beePalette) private var palette
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var settings = AppSettings.shared

    var body: some View {
        ZStack {
            palette.canvas.ignoresSafeArea()
            VStack(spacing: 10) {
                ForEach(1...4, id: \.self) { index in
                    Button {
                        settings.switchProfile(index)
                        dismiss()
                    } label: {
                        HStack {
                            Text(BeeStrings.t(AppSettings.profileNames[index - 1]))
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(palette.ink)
                            Spacer()
                            if settings.profile == index {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundColor(palette.primary)
                            }
                        }
                        .padding(14)
                        .background(RoundedRectangle(cornerRadius: 12).fill(palette.surface))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(
                            settings.profile == index ? palette.primary : palette.divider, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(16)
        }
        .navigationTitle(BeeStrings.t("Profile"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct SubscriptionSheet: View {
    var body: some View { SubscriptionView() }
}

struct MtuSheet: View {
    @Environment(\.beePalette) private var palette
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var settings = AppSettings.shared
    @State private var value = ""
    @State private var savedNote = false

    var body: some View {
        ZStack {
            palette.canvas.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 14) {
                Text(BeeStrings.t("MTU"))
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(palette.ink)
                TextField(faDigits("1280"), text: $value)
                    .keyboardType(.numberPad)
                    .foregroundColor(palette.ink)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(palette.surface))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.divider, lineWidth: 1))
                Text(BeeStrings.t("Leave blank for 1280") + " · " + BeeStrings.t("Applied on the next connect"))
                    .font(.system(size: 11))
                    .foregroundColor(palette.muted)
                HoneyButton(title: BeeStrings.t("Save")) {
                    settings.mtu = value.trimmingCharacters(in: .whitespaces)
                    savedNote = true
                }
                if savedNote {
                    Text(BeeStrings.t("MTU saved"))
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(palette.ok)
                }
                Spacer()
            }
            .padding(16)
        }
        .onAppear { value = settings.mtu }
    }
}

/// Backup / restore — the same four-profile JSON the Android backup carries.
struct BackupSheet: View {
    enum Mode { case export, import }
    let mode: Mode

    @Environment(\.beePalette) private var palette
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var settings = AppSettings.shared
    @State private var text = ""
    @State private var note: String?

    var body: some View {
        NavigationStack {
            ZStack {
                palette.canvas.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        if mode == .export {
                            Text(BeeStrings.t("Copy the JSON and keep it somewhere safe"))
                                .font(.system(size: 12))
                                .foregroundColor(palette.muted)
                        } else {
                            Text(BeeStrings.t("Paste the backup JSON here"))
                                .font(.system(size: 12))
                                .foregroundColor(palette.muted)
                        }
                        MonoEditor(text: $text)
                            .frame(minHeight: 260)
                            .padding(8)
                            .background(RoundedRectangle(cornerRadius: 12).fill(palette.surface))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.divider, lineWidth: 1))
                        if mode == .export {
                            HoneyButton(title: BeeStrings.t("COPY")) {
                                UIPasteboard.general.string = text
                                note = BeeStrings.t("Backup exported")
                            }
                        } else {
                            HoneyButton(title: BeeStrings.t("Import")) {
                                if settings.importJSON(text) {
                                    note = BeeStrings.t("Backup restored")
                                } else {
                                    note = BeeStrings.t("Invalid backup JSON")
                                }
                            }
                        }
                        if let note {
                            Text(note)
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(palette.ok)
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle(mode == .export ? BeeStrings.t("Back up settings") : BeeStrings.t("Restore settings"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(BeeStrings.t("Done")) { dismiss() }
                        .foregroundColor(palette.primary)
                }
            }
            .onAppear {
                if mode == .export { text = settings.exportJSON() }
            }
        }
    }
}
