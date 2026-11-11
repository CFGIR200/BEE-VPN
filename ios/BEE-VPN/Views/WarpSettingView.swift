import SwiftUI

/// Warp Setting — Android's openWarpSettingScreen: the transport rail, the
/// Identity Vault with its four provisioning methods, the terminal tail and
/// the endpoint scanner. On iOS the vault runs the native registration; the
/// relay / ECH / SHARD methods and the exit chain live in the Android core.
struct WarpSettingView: View {
    @Environment(\.beePalette) private var palette
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var tunnel = TunnelControllerHolder.shared.controller

    @State private var provisioning = false
    @State private var note: String?

    var body: some View {
        NavigationStack {
            ZStack {
                palette.canvas.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        rail
                        identityVault
                        exitRoute
                        scanner
                        if let note {
                            Text(note)
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(palette.ok)
                        }
                    }
                    .padding(14)
                }
            }
            .navigationTitle(BeeStrings.t("Warp Setting"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(BeeStrings.t("Done")) { dismiss() }
                        .foregroundColor(palette.primary)
                }
            }
        }
    }

    // ------------------------------------------------------------ rail

    private var rail: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                railChip("MASQUE", enabled: false)
                railChip("WireGuard", enabled: true)
                railChip("WoW", enabled: false)
                railChip("MiM", enabled: false)
            }
        }
    }

    private func railChip(_ title: String, enabled: Bool) -> some View {
        Text(title)
            .font(.system(size: 13, weight: .bold))
            .foregroundColor(enabled ? palette.primary : palette.muted.opacity(0.7))
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Capsule().fill(enabled ? palette.surface : palette.surface.opacity(0.5)))
            .overlay(Capsule().stroke(enabled ? palette.primary : palette.divider, lineWidth: 1))
            .onTapGesture {
                if !enabled { note = BeeStrings.t("Only the native WARP registration runs on iOS") }
            }
    }

    // ------------------------------------------------------------ identity vault

    private var identityVault: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(BeeStrings.t("Identity Vault"))
                .font(.system(size: 11, weight: .heavy))
                .foregroundColor(palette.muted)

            // 2×2 grid — MASQUE / MiM / WireGuard with a ✓ when stored
            VStack(spacing: 8) {
                HStack(spacing: 8) {
                    vaultCell(title: "MASQUE", stored: false)
                    vaultCell(title: "MiM", stored: false)
                }
                HStack(spacing: 8) {
                    vaultCell(title: "WireGuard", stored: WarpProvisioner.saved() != nil)
                    vaultCell(title: "—", stored: false)
                }
            }

            // method row: Relay / Quantum ECH / SHARD / Native Link
            HStack(spacing: 8) {
                methodChip(BeeStrings.t("Relay"), enabled: false)
                methodChip(BeeStrings.t("Quantum ECH"), enabled: false)
                methodChip(BeeStrings.t("SHARD"), enabled: false)
                methodChip(BeeStrings.t("Native Link"), enabled: true)
            }

            Button {
                Task { await provision() }
            } label: {
                Text(provisioning
                     ? BeeStrings.t("PROVISIONING — TAP TO CANCEL")
                     : BeeStrings.t("START — GET IDENTITY"))
                    .font(.system(size: 14, weight: .heavy, design: .monospaced))
                    .foregroundColor(provisioning ? palette.danger : palette.canvas)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(RoundedRectangle(cornerRadius: 12)
                        .fill(provisioning ? palette.surface : palette.primary))
                    .overlay(RoundedRectangle(cornerRadius: 12)
                        .stroke(provisioning ? palette.danger : Color.clear, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .disabled(provisioning)

            terminalTail
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(palette.surface))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(palette.divider, lineWidth: 1))
    }

    private func vaultCell(title: String, stored: Bool) -> some View {
        HStack {
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(stored ? palette.ink : palette.muted.opacity(0.6))
            Spacer()
            if stored {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundColor(palette.ok)
                    .font(.system(size: 14))
                Text(BeeStrings.t("Key received"))
                    .font(.system(size: 10))
                    .foregroundColor(palette.ok)
            } else {
                Text(BeeStrings.t("Get key"))
                    .font(.system(size: 10))
                    .foregroundColor(palette.muted.opacity(0.6))
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10).fill(palette.track))
    }

    private func methodChip(_ title: String, enabled: Bool) -> some View {
        Text(title)
            .font(.system(size: 11, weight: .semibold))
            .foregroundColor(enabled ? palette.ok : palette.muted.opacity(0.6))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Capsule().stroke(enabled ? palette.ok.opacity(0.6) : palette.divider, lineWidth: 1))
            .lineLimit(1)
            .minimumScaleFactor(0.7)
    }

    private var terminalTail: some View {
        Group {
            if !logTail.isEmpty {
                Text(logTail)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundColor(palette.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                    .background(RoundedRectangle(cornerRadius: 10).fill(palette.track))
                    .lineLimit(6)
            }
        }
    }

    private var logTail: String {
        SharedStore.tail([SharedStore.appLog, SharedStore.tunnelLog], lines: 8)
    }

    private func provision() async {
        provisioning = true
        note = nil
        do {
            let identity = try await WarpProvisioner.register()
            note = BeeStrings.t("Identity is stored")
            BeeLog.write("identity registered: device \(identity.deviceId.prefix(8))…")
        } catch {
            let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            tunnel.phase = .failed(message)
            BeeLog.write("provisioning failed: \(message)")
        }
        provisioning = false
    }

    // ------------------------------------------------------------ exit route

    private var exitRoute: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("▸ " + BeeStrings.t("Exit Route"))
                .font(.system(size: 12, weight: .bold, design: .monospaced))
                .foregroundColor(palette.muted)
            HStack(spacing: 8) {
                Text(BeeStrings.t("Direct"))
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(palette.ok)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Capsule().stroke(palette.ok.opacity(0.5), lineWidth: 1))
                Text(BeeStrings.t("Exit-Node"))
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(palette.muted.opacity(0.6))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Capsule().stroke(palette.divider, lineWidth: 1))
            }
            Text(BeeStrings.t("The exit chain runs on the Android engine"))
                .font(.system(size: 11))
                .foregroundColor(palette.muted.opacity(0.85))
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(palette.surface))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(palette.divider, lineWidth: 1))
    }

    // ------------------------------------------------------------ scanner

    private var scanner: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text(BeeStrings.t("Scanner: Normal"))
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(palette.muted)
            }
            Text(BeeStrings.t("The engine scanner is part of the Android core"))
                .font(.system(size: 11))
                .foregroundColor(palette.muted.opacity(0.85))
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(palette.surface))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(palette.divider, lineWidth: 1))
    }
}
