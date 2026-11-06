import Foundation

enum LicenseError: LocalizedError {
    case badURL
    case network(String)
    case server(String)   // the JSON {ok:false, error:"..."} from the worker

    var errorDescription: String? {
        switch self {
        case .badURL: return "آدرس سرور لایسنس تنظیم نشده است"
        case .network(let m): return "خطای شبکه — به اینترنت وصل باش و دوباره تلاش کن (\(m))"
        case .server(let m): return faMessage(workerError: m)
        }
    }

    /// Worker errors → buyer-readable Persian (same tone as the bot).
    private func faMessage(workerError: String) -> String {
        switch workerError {
        case "unknown code": return "این کد فعال‌سازی وجود ندارد — آن را با فاکتور تلگرام مقایسه کن"
        case "invalid code format": return "قالب کد درست نیست — کد شکل BEE-XXXX-XXXX-XXXX دارد"
        case "revoked": return "این اشتراک باطل شده است — با پشتیبانی تماس بگیر"
        case "expired": return "اشتراک به پایان رسیده — برای ادامه تمدید کن"
        case "bound to another device": return "این اشتراک روی دستگاه دیگری فعال است — برای انتقال با پشتیبانی تماس بگیر"
        case "wrong username or password": return "نام کاربری یا رمز عبور اشتباه است"
        case "too many attempts, try later": return "تلاش‌های زیاد — یک ساعت دیگر دوباره امتحان کن"
        case "invalid username or password": return "نام کاربری یا رمز عبور نامعتبر است"
        case "invalid install_id", "invalid token": return "شناسهٔ دستگاه نامعتبر است — اپ را دوباره باز کن"
        default: return "خطا از سرور: \(workerError)"
        }
    }
}

/// Thin URLSession client for the license Worker — /activate /login /verify.
/// All POST, JSON in / JSON out, exactly like LicenseManager.kt.
enum LicenseAPI {

    private static func post(_ path: String, body: [String: Any]) async throws -> [String: Any] {
        guard !BeeConfig.licenseURL.isEmpty, let url = URL(string: BeeConfig.licenseURL.trimmingCharacters(in: ["/"]) + path) else {
            throw LicenseError.badURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            throw LicenseError.network(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else {
            throw LicenseError.network("پاسخ نامعتبر")
        }
        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw LicenseError.network("پاسخ غیر JSON از سرور (کد \(http.statusCode))")
        }
        if (obj["ok"] as? Bool) == true { return obj }
        throw LicenseError.server((obj["error"] as? String) ?? "خطای ناشناخته (کد \(http.statusCode))")
    }

    /// POST /activate { code, install_id } → binds a code to this device and
    /// starts the subscription clock at first activation.
    static func activate(code: String, installId: String) async throws -> [String: Any] {
        try await post("/activate", body: ["code": code, "install_id": installId])
    }

    /// POST /login { username, password, install_id }.
    static func login(username: String, password: String, installId: String) async throws -> [String: Any] {
        try await post("/login", body: ["username": username, "password": password, "install_id": installId])
    }

    /// POST /verify { token, install_id } → re-check revocation + expiry,
    /// returns a fresh token. The app runs it every ~12 h and on resume.
    static func verify(token: String, installId: String) async throws -> [String: Any] {
        try await post("/verify", body: ["token": token, "install_id": installId])
    }
}
