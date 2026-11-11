import Foundation
import Combine

/// Four independent setting sets, the same layering as Android Profiles.kt:
/// every key is stored under a "p1_"…"p4_" prefix, and switching profile
/// switches the prefix — each profile keeps its own protocol, protection,
/// routing and shaping choices.
@MainActor
final class AppSettings: ObservableObject {
    static let shared = AppSettings()
    static let profileNames = ["Profile A", "Profile B", "Profile C", "Profile D"]

    @Published private(set) var profile: Int

    // PROTECTION
    @Published var killSwitch: Bool { didSet { set("kill_switch", killSwitch) } }
    @Published var autoReconnect: Bool { didSet { set("auto_reconnect", autoReconnect) } }
    // ROUTING & DATA
    @Published var bypassIran: Bool { didSet { set("iran_bypass", bypassIran) } }
    @Published var lanAccess: Bool { didSet { set("lan_access", lanAccess) } }
    // CONNECTION
    @Published var tunnelProtocol: String { didSet { set("default_protocol", tunnelProtocol) } }
    @Published var manualEndpoint: String { didSet { set("manual_endpoint", manualEndpoint) } }
    @Published var customDNS: String { didSet { set("dns_servers", customDNS) } }
    @Published var mtu: String { didSet { set("mtu_wg", mtu) } }

    /// The endpoint the last successful ladder rung answered on — the iOS
    /// counterpart of Android's remembered AUTO-Scan winner.
    @Published var lastGoodEndpoint: String {
        didSet { set("last_good_endpoint", lastGoodEndpoint) }
    }
    /// One-time auto-scan latch, like Android's auto_scan_done.
    @Published private(set) var autoScanDone: Bool

    private init() {
        // init order rule: read via a local — self.profile is not usable
        // until every stored property is initialized.
        let p = UserDefaults.standard.object(forKey: "active_profile") as? Int ?? 1
        killSwitch = UserDefaults.standard.bool(forKey: "p\(p)kill_switch")
        autoReconnect = UserDefaults.standard.bool(forKey: "p\(p)auto_reconnect")
        bypassIran = UserDefaults.standard.bool(forKey: "p\(p)iran_bypass")
        lanAccess = UserDefaults.standard.object(forKey: "p\(p)lan_access") as? Bool ?? false
        tunnelProtocol = UserDefaults.standard.string(forKey: "p\(p)default_protocol") ?? "wireguard"
        manualEndpoint = UserDefaults.standard.string(forKey: "p\(p)manual_endpoint") ?? ""
        customDNS = UserDefaults.standard.string(forKey: "p\(p)dns_servers") ?? ""
        mtu = UserDefaults.standard.string(forKey: "p\(p)mtu_wg") ?? ""
        lastGoodEndpoint = UserDefaults.standard.string(forKey: "p\(p)last_good_endpoint") ?? ""
        profile = p
        autoScanDone = UserDefaults.standard.bool(forKey: "p\(p)auto_scan_done")
    }

    func switchProfile(_ index: Int) {
        guard (1...4).contains(index), index != profile else { return }
        profile = index
        UserDefaults.standard.set(index, forKey: "active_profile")
        killSwitch = UserDefaults.standard.bool(forKey: "p\(profile)kill_switch")
        autoReconnect = UserDefaults.standard.bool(forKey: "p\(profile)auto_reconnect")
        bypassIran = UserDefaults.standard.bool(forKey: "p\(profile)iran_bypass")
        lanAccess = UserDefaults.standard.object(forKey: "p\(profile)lan_access") as? Bool ?? false
        tunnelProtocol = UserDefaults.standard.string(forKey: "p\(profile)default_protocol") ?? "wireguard"
        manualEndpoint = UserDefaults.standard.string(forKey: "p\(profile)manual_endpoint") ?? ""
        customDNS = UserDefaults.standard.string(forKey: "p\(profile)dns_servers") ?? ""
        mtu = UserDefaults.standard.string(forKey: "p\(profile)mtu_wg") ?? ""
        lastGoodEndpoint = UserDefaults.standard.string(forKey: "p\(profile)last_good_endpoint") ?? ""
        autoScanDone = UserDefaults.standard.bool(forKey: "p\(profile)auto_scan_done")
        BeeLog.write("profile switched to p\(index)")
    }

    func markAutoScanDone() {
        autoScanDone = true
        UserDefaults.standard.set(true, forKey: "p\(profile)auto_scan_done")
    }

    func resetAutoScanDone() {
        autoScanDone = false
        UserDefaults.standard.set(false, forKey: "p\(profile)auto_scan_done")
    }

    // --------------------------------------------------------- backup

    private var backupKeys: [String] {
        ["kill_switch", "auto_reconnect", "iran_bypass", "lan_access",
         "default_protocol", "manual_endpoint", "dns_servers", "mtu_wg",
         "last_good_endpoint", "auto_scan_done"]
    }

    func exportJSON() -> String {
        var dict: [String: Any] = [
            "version": 1,
            "profile": profile,
        ]
        for key in backupKeys {
            let full = "p\(profile)\(key)"
            if let v = UserDefaults.standard.object(forKey: full) {
                dict[key] = v
            }
        }
        guard let data = try? JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys]),
              let text = String(data: data, encoding: .utf8) else { return "{}" }
        return text
    }

    func importJSON(_ text: String) -> Bool {
        guard let data = text.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return false
        }
        for key in backupKeys {
            guard let value = dict[key] else { continue }
            let full = "p\(profile)\(key)"
            switch value {
            case let b as Bool: UserDefaults.standard.set(b, forKey: full)
            case let n as Int where key == "auto_scan_done": UserDefaults.standard.set(n == 1, forKey: full)
            case let s as String: UserDefaults.standard.set(s, forKey: full)
            default: break
            }
        }
        switchProfileReload()
        return true
    }

    func resetToDefaults() {
        for key in backupKeys {
            UserDefaults.standard.removeObject(forKey: "p\(profile)\(key)")
        }
        switchProfileReload()
        BeeLog.write("settings reset to defaults (p\(profile))")
    }

    private func switchProfileReload() {
        let index = profile
        switchProfile(index == 4 ? 1 : index + 1)
        switchProfile(index)
    }

    // --------------------------------------------------------- storage

    private func set(_ key: String, _ value: Bool) {
        UserDefaults.standard.set(value, forKey: "p\(profile)\(key)")
    }

    private func set(_ key: String, _ value: String) {
        UserDefaults.standard.set(value, forKey: "p\(profile)\(key)")
    }
}

// MARK: - Shared container (app ⇄ tunnel extension logs)

/// The app group container both processes share. The tunnel extension writes
/// `tunnel.log` from inside the VPN; the app writes `app.log` here too —
/// the Logs screen shows them the way Android shows All / App / Core.
enum SharedStore {
    static let groupIdentifier = "group.app.beevpn"
    static let tunnelLog = "tunnel.log"
    static let appLog = "app.log"
    static let logCapBytes = 256 * 1024

    static var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupIdentifier)
    }

    static func append(_ file: String, _ line: String) {
        guard let dir = containerURL else { return }
        let url = dir.appendingPathComponent(file)
        let stamped = line + "\n"
        if let handle = try? FileHandle(forWritingTo: url) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            _ = try? handle.write(contentsOf: Data(stamped.utf8))
        } else {
            try? Data(stamped.utf8).write(to: url)
        }
        trim(file: file, url: url)
    }

    /// 256 KB rolling cap, the same budget Android's connection.log keeps.
    private static func trim(file: String, url: URL) {
        guard let size = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int,
              size > logCapBytes,
              let data = try? Data(contentsOf: url) else { return }
        let keep = data.suffix(logCapBytes / 2)
        // drop a possibly torn first line
        if let nl = keep.firstIndex(of: 0x0A) {
            try? keep[(keep.index(after: nl))...].write(to: url)
        } else {
            try? keep.write(to: url)
        }
    }

    static func read(_ file: String) -> String {
        guard let dir = containerURL,
              let data = try? Data(contentsOf: dir.appendingPathComponent(file)),
              let text = String(data: data, encoding: .utf8) else { return "" }
        return text
    }

    static func tail(_ files: [String], lines: Int) -> String {
        let joined = files.map { read($0) }.filter { !$0.isEmpty }.joined(separator: "\n")
        let parts = joined.split(separator: "\n")
        return parts.suffix(lines).joined(separator: "\n")
    }

    static func clear(_ files: [String]) {
        guard let dir = containerURL else { return }
        for file in files {
            try? Data().write(to: dir.appendingPathComponent(file))
        }
    }
}

// MARK: - App-side log with the Android redaction rule

/// App process log for the Logs screen. Tokens (`v1.…`) are redacted before
/// they touch disk — the same promise Android's LogRedactor makes.
enum BeeLog {
    static func write(_ line: String) {
        let stamp: String = {
            let fmt = DateFormatter()
            fmt.dateFormat = "HH:mm:ss"
            return fmt.string(from: Date())
        }()
        SharedStore.append(SharedStore.appLog, "[\(stamp)] \(redact(line))")
    }

    static func redact(_ line: String) -> String {
        var out = line
        while let r = out.range(of: "v1\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+", options: .regularExpression) {
            out.replaceSubrange(r, with: "v1.[redacted]")
        }
        return out
    }
}

// MARK: - Version

enum AppVersion {
    static var short: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "3.6.10"
    }
    static var build: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "471"
    }
}
