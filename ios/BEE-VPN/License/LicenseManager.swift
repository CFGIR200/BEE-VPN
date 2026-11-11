import Foundation
import UIKit
import Combine

/// The iOS mirror of LicenseManager.kt — one source of truth for the
/// entitlement on this device.
///
///   * activate / login talk to the Worker and store the Ed25519 token in the
///     Keychain,
///   * entitlement() re-verifies the token OFFLINE on every read (signature +
///     device binding + clock) — the app keeps working until the token's own
///     `e`, exactly like Android,
///   * verifyInBackground() runs /verify every 6 h and on foreground so
///     revocation and admin extends propagate (Android uses ~12 h; a tighter
///     iOS cadence costs one tiny request).
@MainActor
final class LicenseManager: ObservableObject {

    static let shared = LicenseManager()

    enum State: Equatable {
        case unknown
        case granted
        case locked(reason: String?)
    }

    @Published private(set) var state: State = .unknown
    @Published private(set) var lastVerifyAt: Int64 = 0

    // Keychain keys (prefixed so the WARP identity can share the same service)
    static let kToken = "license.token"
    static let kSubject = "license.subject"
    static let kKind = "license.kind"
    static let kActivatedAt = "license.activatedAt"
    static let kLastCheck = "license.lastCheck"

    private var timer: Timer?

    var isConfigured: Bool {
        !BeeConfig.licenseURL.isEmpty && !BeeConfig.licensePubKeyB64.isEmpty
    }

    /// 32 hex chars — the same shape Android generates (UUID without dashes)
    /// and the same shape the Worker's normalizeInstallId accepts.
    var installId: String {
        if let saved = KeychainStore.string(forKey: "license.installId"), saved.count == 32 {
            return saved
        }
        let fresh = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        KeychainStore.setString(fresh, forKey: "license.installId")
        return fresh
    }

    var storedToken: String? {
        let t = KeychainStore.string(forKey: Self.kToken)
        return (t?.isEmpty == false) ? t : nil
    }

    private init() {
        refreshState()
        startBackgroundVerification()
    }

    // ------------------------------------------------------------- state

    func refreshState() {
        guard isConfigured else {
            // Build without a license server: gate passes through, like Android.
            state = .granted
            return
        }
        guard let token = storedToken else {
            state = .locked(reason: nil)
            return
        }
        if BeeCrypto.verifyToken(token, pubKeyB64: BeeConfig.licensePubKeyB64, installId: installId) != nil {
            state = .granted
        } else {
            state = .locked(reason: nil)
        }
    }

    // ------------------------------------------------------------ actions

    /// Activates a BEE-XXXX-XXXX-XXXX code. Returns nil on success, a
    /// buyer-readable Persian error otherwise.
    func activate(code: String) async -> String? {
        let clean = code.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let resp = try await LicenseAPI.activate(code: clean, installId: installId)
            return store(from: resp)
        } catch {
            return (error as? LicenseError)?.errorDescription ?? error.localizedDescription
        }
    }

    func login(username: String, password: String) async -> String? {
        do {
            let resp = try await LicenseAPI.login(username: username, password: password, installId: installId)
            return store(from: resp)
        } catch {
            return (error as? LicenseError)?.errorDescription ?? error.localizedDescription
        }
    }

    /// Persists a successful /activate or /login response and flips state.
    private func store(from resp: [String: Any]) -> String? {
        guard let token = resp["token"] as? String,
              let subject = resp["subject"] as? String,
              let plan = resp["plan"] as? String,
              let kind = resp["kind"] as? String else {
            return "پاسخ سرور ناقص بود — دوباره تلاش کن"
        }
        // Trust only after our own Ed25519 verification, like the Android app.
        guard BeeCrypto.verifyToken(token, pubKeyB64: BeeConfig.licensePubKeyB64, installId: installId) != nil else {
            return "امضای توکن سرور معتبر نبود — با پشتیبانی تماس بگیر"
        }
        let now = Int64(Date().timeIntervalSince1970)
        let hadToken = storedToken != nil
        KeychainStore.setString(token, forKey: Self.kToken)
        KeychainStore.setString(subject, forKey: Self.kSubject)
        KeychainStore.setString(kind, forKey: Self.kKind)
        if !hadToken {
            KeychainStore.setString(String(now), forKey: Self.kActivatedAt)
        }
        KeychainStore.setString(String(now), forKey: Self.kLastCheck)
        lastVerifyAt = now
        state = .granted
        return nil
    }

    /// /verify in the background: revocation + admin extends land here.
    /// Never blocks the UI; on hard failure the offline token keeps deciding.
    func verifyInBackground() {
        guard isConfigured, let token = storedToken else { return }
        Task { [weak self] in
            guard let self else { return }
            do {
                let resp = try await LicenseAPI.verify(token: token, installId: self.installId)
                if let fresh = resp["token"] as? String,
                   BeeCrypto.verifyToken(fresh, pubKeyB64: BeeConfig.licensePubKeyB64, installId: self.installId) != nil {
                    let now = Int64(Date().timeIntervalSince1970)
                    KeychainStore.setString(fresh, forKey: Self.kToken)
                    KeychainStore.setString(String(now), forKey: Self.kLastCheck)
                    self.lastVerifyAt = now
                    self.state = .granted
                }
            } catch LicenseError.server(let message) {
                if message == "revoked" || message == "expired" {
                    // The server says no — the offline clock must not override it.
                    self.state = .locked(reason: LicenseError.server(message).errorDescription)
                }
            } catch {
                // network trouble: keep the offline verdict
            }
        }
    }

    private func startBackgroundVerification() {
        let center = NotificationCenter.default
        center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { [weak self] in
                await self?.verifyInBackground()
            }
        }
        timer = Timer.scheduledTimer(withTimeInterval: 6 * 3600, repeats: true) { [weak self] _ in
            Task { [weak self] in
                await self?.verifyInBackground()
            }
        }
    }

    // ------------------------------------------------- subscription info

    /// One-line summary for the Settings SUBSCRIPTION row — the same shape
    /// Android prints under the row ("%d days left" / "Not active").
    var subscriptionSummary: String {
        guard isConfigured else { return BeeStrings.t("Not active") }
        guard let token = storedToken,
              let e = BeeCrypto.verifyToken(token, pubKeyB64: BeeConfig.licensePubKeyB64, installId: installId) else {
            return BeeStrings.t("Not active")
        }
        let now = Int64(Date().timeIntervalSince1970)
        let days = max(0, Int((e.expiry - now) / 86_400))
        return BeeStrings.tf("%d days left", faDigits("\(days)"))
    }

    /// The same numbers the VPN gate enforces — the profile screen reads this,
    /// so the profile can never disagree with the gate (like Android's
    /// SubscriptionInfoActivity).
    struct SubscriptionInfo {
        var configured: Bool
        var hasToken: Bool
        var active: Bool
        var expired: Bool
        var plan: String
        var kind: String
        var subject: String
        var installId: String
        var expiryEpochSec: Int64
        var activatedAtEpochSec: Int64
        var lastCheckEpochSec: Int64
    }

    func subscriptionInfo() -> SubscriptionInfo {
        let now = Int64(Date().timeIntervalSince1970)
        guard let token = storedToken,
              let e = BeeCrypto.verifyToken(token, pubKeyB64: BeeConfig.licensePubKeyB64, installId: installId) else {
            return SubscriptionInfo(configured: isConfigured, hasToken: false, active: false, expired: false,
                                    plan: "", kind: "", subject: "", installId: installId,
                                    expiryEpochSec: 0, activatedAtEpochSec: 0, lastCheckEpochSec: 0)
        }
        let activatedAt = Int64(KeychainStore.string(forKey: Self.kActivatedAt) ?? "") ?? 0
        let lastCheck = Int64(KeychainStore.string(forKey: Self.kLastCheck) ?? "") ?? 0
        return SubscriptionInfo(configured: isConfigured, hasToken: true, active: true, expired: false,
                                plan: e.plan, kind: e.kind, subject: e.subject, installId: installId,
                                expiryEpochSec: e.expiry, activatedAtEpochSec: activatedAt,
                                lastCheckEpochSec: lastCheck > 0 ? lastCheck : now)
    }
}
