import Foundation

/// IPv4 routing overlay for the Bypass-Iran / LAN toggles — the iOS
/// counterpart of MsnGuardVpnService.applyIranBypass().
///
/// Android excludes routes at the VPN layer (`excludeRoute`), which covers
/// every transport. iOS WireGuard builds its routing table purely from the
/// wg-quick AllowedIPs, so the same effect is achieved by tunneling the
/// *complement* of the excluded ranges instead of 0.0.0.0/0. The exclusion
/// list is the very same `geoip-iran.cidr` asset the Android app ships.
enum Routing {

    struct Range: Equatable {
        var start: UInt32   // inclusive
        var end: UInt32     // inclusive
    }

    /// "2.144.0.0/14" → Range. Returns nil for junk lines and IPv6.
    static func parseCIDR(_ line: String) -> Range? {
        let parts = line.split(separator: "/")
        guard parts.count == 2, let prefix = Int(parts[1]), prefix >= 0, prefix <= 32 else { return nil }
        let octets = parts[0].split(separator: ".")
        guard octets.count == 4 else { return nil }
        var ip: UInt32 = 0
        for o in octets {
            guard let byte = UInt32(o), byte <= 255 else { return nil }
            ip = (ip << 8) | byte
        }
        if prefix == 0 { return Range(start: 0, end: UInt32.max) }
        let size = UInt32(1) << UInt32(32 - prefix)
        let start = ip & ~(size - 1)
        let end = start &+ (size - 1)
        return Range(start: start, end: end)
    }

    /// Merges overlapping/adjacent ranges so the complement walk stays short.
    static func merge(_ input: [Range]) -> [Range] {
        let sorted = input.filter { $0.start <= $0.end }.sorted { $0.start < $1.start }
        var out: [Range] = []
        for r in sorted {
            if var last = out.last, r.start <= last.end &+ 1 {
                if r.end > last.end { last.end = r.end; out[out.count - 1] = last }
            } else {
                out.append(r)
            }
        }
        return out
    }

    /// The gaps between the excluded ranges — the traffic that stays tunnelled.
    static func complement(excluded: [Range]) -> [Range] {
        let merged = merge(excluded)
        var out: [Range] = []
        var cursor: UInt64 = 0
        for r in merged {
            let s = UInt64(r.start)
            if s > cursor {
                out.append(Range(start: UInt32(cursor), end: UInt32(s - 1)))
            }
            cursor = max(cursor, UInt64(r.end) + 1)
            if cursor > UInt64(UInt32.max) { return out }
        }
        if cursor <= UInt64(UInt32.max) {
            out.append(Range(start: UInt32(cursor), end: UInt32.max))
        }
        return out
    }

    /// Minimal CIDR cover of a contiguous range (standard bit-split walk).
    static func cidrCover(_ range: Range) -> [String] {
        var out: [String] = []
        var start: UInt64 = UInt64(range.start)
        let end: UInt64 = UInt64(range.end)
        while start <= end {
            var size: UInt64 = 1
            while (start % (size << 1)) == 0, start + (size << 1) - 1 <= end {
                size <<= 1
            }
            while start + size - 1 > end {
                size >>= 1
            }
            let prefix = UInt32(32 - log2(Double(size)).rounded(.down))
            out.append("\(ipv4String(UInt32(start)))/\(prefix)")
            start += size
        }
        return out
    }

    static func ipv4String(_ ip: UInt32) -> String {
        [24, 16, 8, 0].map { String((ip >> UInt32($0)) & 0xFF) }.joined(separator: ".")
    }

    /// RFC1918 private space — never routes into the tunnel, so printers,
    /// NAS boxes and the router stay reachable (Android's "Local network
    /// access" toggle does exactly this at the VPN layer).
    static let privateCIDRs = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"]

    /// Loads the shipped geoip-iran.cidr (2,235 IPv4 ranges — the identical
    /// asset Android bundles). Private space and /8-and-shorter prefixes are
    /// skipped with the same rule MsnGuardVpnService applies: excluding them
    /// blackholes the tunnel's own address plan or the whole line.
    static func iranCIDRs() -> [String] {
        guard let url = Bundle.main.url(forResource: "geoip-iran", withExtension: "cidr"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            BeeLog.write("geoip-iran.cidr missing — bypass Iran falls back to full tunnel")
            return []
        }
        var out: [String] = []
        for raw in text.split(separator: "\n") {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("#") { continue }
            guard let range = parseCIDR(line) else { continue }
            let firstByte = (range.start >> 24) & 0xFF
            let secondByte = (range.start >> 16) & 0xFF
            let isPrivate =
                firstByte == 10 ||
                (firstByte == 172 && secondByte >= 16 && secondByte <= 31) ||
                (firstByte == 192 && secondByte == 168)
            let prefix = range.prefixLength
            if isPrivate || prefix <= 7 { continue }
            out.append(line)
        }
        return out
    }
}

extension Routing.Range {
    /// Prefix length recovered from a parsed CIDR (block size back to bits).
    var prefixLength: UInt32 {
        let size = end - start + 1
        if size == 0 || (size & (size - 1)) != 0 { return 32 }
        var p: UInt32 = 32
        var s = size
        while s > 1 { s >>= 1; p -= 1 }
        return p
    }
}

/// Builds the final wg-quick text the tunnel extension consumes: DNS / MTU /
/// AllowedIPs overlays on top of the provisioned identity (or a manual
/// wg-quick paste). This is where the profile's routing decisions — bypass
/// Iran, local network access, custom DNS, MTU — become WireGuard config.
enum TunnelConfigBuilder {

    static func build(base: String,
                      dns: String,
                      mtu: String,
                      bypassIran: Bool,
                      lanAccess: Bool) -> (config: String, routesNote: String?) {
        var lines = base.split(separator: "\n").map(String.init)
        var note: String?

        // --- DNS overlay
        let dnsTrim = dns.trimmingCharacters(in: .whitespacesAndNewlines)
        if !dnsTrim.isEmpty {
            lines = replaceLine(prefix: "DNS", in: lines, with: "DNS = \(dnsTrim)")
        }

        // --- MTU overlay
        let mtuTrim = mtu.trimmingCharacters(in: .whitespacesAndNewlines)
        if let mtuValue = UInt16(mtuTrim), mtuValue >= 576 {
            lines = replaceLine(prefix: "MTU", in: lines, with: "MTU = \(mtuValue)")
        }

        // --- AllowedIPs overlay (bypass Iran / LAN)
        //
        // excluded = ranges that leave through the carrier link:
        //   · bypass Iran ON  → the geoip-iran ranges
        //   · LAN access ON   → RFC1918 (printers, NAS, router stay reachable)
        // The tunnel carries the complement, expressed as minimal CIDRs —
        // with both toggles off the base 0.0.0.0/0 is kept untouched.
        if bypassIran || lanAccess {
            var excluded: [Routing.Range] = []
            if bypassIran {
                excluded += Routing.iranCIDRs().compactMap { Routing.parseCIDR($0) }
            }
            if lanAccess {
                excluded += Routing.privateCIDRs.compactMap { Routing.parseCIDR($0) }
            }
            let gaps = Routing.complement(excluded: excluded)
            let cidrs = gaps.flatMap { Routing.cidrCover($0) }
            if cidrs.count <= 6000 {
                var v6All = ""
                for line in lines where line.hasPrefix("AllowedIPs") {
                    if line.contains("::/0") { v6All = ", ::/0" }
                }
                let joined = cidrs.joined(separator: ",")
                lines = replaceLine(prefix: "AllowedIPs", in: lines,
                                    with: "AllowedIPs = \(joined)\(v6All)")
                note = faDigits("\(cidrs.count)") + " مسیر IPv4 در تونل رفت — محدوده‌های مستثنا از لینک اپراتور خارج می‌شوند"
            } else {
                note = "مکمل مسیرها بیش از حد بزرگ بود — تونل کامل حفظ شد"
            }
        }

        return (lines.joined(separator: "\n"), note)
    }

    /// Replaces the first wg-quick line with the given prefix, dropping later
    /// duplicates, and inserting before [Peer] when the base had none.
    private static func replaceLine(prefix: String, in lines: [String], with replacement: String) -> [String] {
        var result: [String] = []
        var replaced = false
        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix(prefix) {
                if !replaced {
                    result.append(replacement)
                    replaced = true
                }
                continue
            }
            result.append(line)
        }
        if !replaced {
            if let peerIdx = result.firstIndex(where: { $0.trimmingCharacters(in: .whitespaces) == "[Peer]" }) {
                result.insert(replacement, at: peerIdx)
            } else {
                result.append(replacement)
            }
        }
        return result
    }

    /// Replaces (or injects) the Endpoint line — the ladder and the manual
    /// endpoint row both funnel through here.
    static func applyEndpoint(_ config: String, _ endpoint: String) -> String {
        let lines = config.split(separator: "\n").map(String.init)
        let replaced = replaceLine(prefix: "Endpoint", in: lines, with: "Endpoint = \(endpoint)")
        return replaced.joined(separator: "\n")
    }
}
