import SwiftUI

/// Tunnel controls — Android's openTunnelControlsScreen: CONNECTION SHAPING,
/// ROUTING, TROUBLESHOOTING, ANTI-DPI. On iOS the routing rows are real
/// (manual endpoint, reset identity, custom DNS); everything that belongs to
/// the Android core engine stays visible but disabled with a reason.
struct TunnelControlsView: View {
    @Environment(\.beePalette) private var palette
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var settings = AppSettings.shared
    @ObservedObject private var tunnel = TunnelControllerHolder.shared.controller

    @State private var dnsField = ""
    @State private var endpointField = ""
    @State private var note: String?
    @State private var showDnsTest = false

    var body: some View {
        NavigationStack {
            ZStack {
                palette.canvas.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 6) {
                        shapingSection
                        routingSection
                        troubleshootingSection
                        antiDpiSection
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                }
            }
            .navigationTitle(BeeStrings.t("Tunnel controls"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(BeeStrings.t("Done")) { dismiss() }
                        .foregroundColor(palette.primary)
                }
            }
        }
    }

    private func section(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(BeeStrings.t(title))
                .font(.system(size: 11, weight: .heavy))
                .foregroundColor(palette.muted)
                .padding(.top, 12)
            content()
        }
    }

    private func disabledRow(_ title: String, reason: String) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(palette.muted)
                Text(reason)
                    .font(.system(size: 11))
                    .foregroundColor(palette.muted.opacity(0.75))
            }
            Spacer()
            Image(systemName: "lock.fill")
                .font(.system(size: 12))
                .foregroundColor(palette.muted.opacity(0.5))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .background(RoundedRectangle(cornerRadius: 12).fill(palette.surface.opacity(0.6)))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.divider, lineWidth: 1))
    }

    // ------------------------------------------------------------ sections

    private var shapingSection: some View {
        section("CONNECTION SHAPING") {
            disabledRow(BeeStrings.t("Obfuscation"),
                        reason: BeeStrings.t("Obfuscation is part of the Android core engine"))
            disabledRow(BeeStrings.t("Advanced obfuscation"),
                        reason: BeeStrings.t("Advanced obfuscation is part of the Android core engine"))
            disabledRow(BeeStrings.t("WireGuard retries"),
                        reason: BeeStrings.t("Not available on iOS"))
        }
    }

    private var routingSection: some View {
        section("ROUTING") {
            VStack(alignment: .leading, spacing: 6) {
                Text(BeeStrings.t("Manual endpoint"))
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(palette.ink)
                Text(BeeStrings.t("IP:port, blank for automatic"))
                    .font(.system(size: 11))
                    .foregroundColor(palette.muted)
                TextField("engage.cloudflareclient.com:2408", text: $endpointField)
                    .keyboardType(.asciiCapable)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .foregroundColor(palette.ink)
                    .padding(10)
                    .background(RoundedRectangle(cornerRadius: 10).fill(palette.track))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(palette.divider, lineWidth: 1))
                HoneyButton(title: BeeStrings.t("Apply")) {
                    settings.manualEndpoint = endpointField.trimmingCharacters(in: .whitespaces)
                    note = BeeStrings.t("Manual endpoint applied")
                    BeeLog.write("manual endpoint set: \(settings.manualEndpoint)")
                }
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(palette.surface))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.divider, lineWidth: 1))
            .onAppear { endpointField = settings.manualEndpoint }

            disabledRow(BeeStrings.t("Inner hop endpoint"),
                        reason: BeeStrings.t("Inner hop chaining is part of the Android core engine"))
            disabledRow(BeeStrings.t("Gateway cache"),
                        reason: BeeStrings.t("Gateway cache belongs to the Android core engine"))

            HStack(spacing: 10) {
                HoneyButton(title: BeeStrings.t("Reset identity"), filled: false) {
                    tunnel.resetIdentity()
                    note = BeeStrings.t("Identity reset")
                }
                HoneyButton(title: BeeStrings.t("New account")) {
                    Task {
                        note = BeeStrings.t("Registration started…")
                        await tunnel.regenerateIdentity()
                        note = BeeStrings.t("Identity regenerated")
                    }
                }
            }

            VStack(alignment: .leading, spacing: 6) {
                Text(BeeStrings.t("Custom DNS"))
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(palette.ink)
                TextField("1.1.1.1 · tls://one.one.one.one · https://dns.google/dns-query", text: $dnsField)
                    .keyboardType(.asciiCapable)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .foregroundColor(palette.ink)
                    .padding(10)
                    .background(RoundedRectangle(cornerRadius: 10).fill(palette.track))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(palette.divider, lineWidth: 1))
                HStack(spacing: 10) {
                    HoneyButton(title: BeeStrings.t("Save")) {
                        settings.customDNS = dnsField.trimmingCharacters(in: .whitespaces)
                        note = BeeStrings.t("Settings saved")
                        BeeLog.write("custom dns set: \(settings.customDNS)")
                    }
                    HoneyButton(title: BeeStrings.t("Test"), filled: false) {
                        Task { await testDns() }
                    }
                }
                Text(BeeStrings.t("DNS test works for https:// (DoH) entries"))
                    .font(.system(size: 10))
                    .foregroundColor(palette.muted.opacity(0.8))
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(palette.surface))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.divider, lineWidth: 1))
            .onAppear { dnsField = settings.customDNS }

            if let note {
                Text(note)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(palette.ok)
            }
        }
    }

    private var troubleshootingSection: some View {
        section("TROUBLESHOOTING") {
            disabledRow(BeeStrings.t("TLS fingerprint"),
                        reason: BeeStrings.t("TLS options are part of the Android core engine"))
            disabledRow(BeeStrings.t("WireGuard verification"),
                        reason: BeeStrings.t("TLS options are part of the Android core engine"))
        }
    }

    private var antiDpiSection: some View {
        section("ANTI-DPI") {
            disabledRow(BeeStrings.t("TLS fragmentation"),
                        reason: BeeStrings.t("Anti-DPI tricks are part of the Android core engine"))
            disabledRow(BeeStrings.t("Mixed-case SNI"),
                        reason: BeeStrings.t("Anti-DPI tricks are part of the Android core engine"))
        }
    }

    // ------------------------------------------------------------ dns test

    private func testDns() async {
        let entry = dnsField.trimmingCharacters(in: .whitespaces)
        guard entry.lowercased().hasPrefix("https://"), let url = URL(string: entry) else {
            note = BeeStrings.t("DNS test works for https:// (DoH) entries")
            return
        }
        var request = URLRequest(url: url.appending(queryItems: [
            URLQueryItem(name: "name", value: "cloudflare.com"),
            URLQueryItem(name: "type", value: "A"),
        ]))
        request.setValue("application/dns-json", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 10
        do {
            let (data, _) = try await URLSession.shared.data(for: request)
            let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            let status = obj?["Status"] as? Int ?? -1
            if status == 0 {
                note = BeeStrings.tf("DNS test passed — %s bytes", faDigits("\(data.count)"))
            } else {
                note = BeeStrings.t("DNS failed")
            }
        } catch is URLError {
            note = BeeStrings.t("Timeout")
        } catch {
            note = BeeStrings.t("DNS failed")
        }
    }
}
