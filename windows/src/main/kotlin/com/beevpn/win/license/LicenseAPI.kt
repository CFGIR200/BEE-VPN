package com.beevpn.win.license

import com.beevpn.win.config.BeeConfig
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/// Thin HttpClient for the license Worker — /activate /login /verify.
/// All POST, JSON in / JSON out, exactly like LicenseManager.kt and
/// LicenseAPI.swift on the other two platforms.
object LicenseAPI {

    sealed class LicenseError : Exception() {
        class BadUrl : LicenseError() {
            override val message: String get() = "آدرس سرور لایسنس تنظیم نشده است"
        }
        class Network(private val m: String) : LicenseError() {
            override val message: String
                get() = "خطای شبکه — به اینترنت وصل باش و دوباره تلاش کن ($m)"
        }
        class Server(private val m: String) : LicenseError() {
            override val message: String get() = faMessage(m)
        }
    }

    /// Worker errors → buyer-readable Persian (same tone as the bot).
    fun faMessage(workerError: String): String = when (workerError) {
        "unknown code" -> "این کد فعال‌سازی وجود ندارد — آن را با فاکتور تلگرام مقایسه کن"
        "invalid code format" -> "قالب کد درست نیست — کد شکل BEE-XXXX-XXXX-XXXX دارد"
        "revoked" -> "این اشتراک باطل شده است — با پشتیبانی تماس بگیر"
        "expired" -> "اشتراک به پایان رسیده — برای ادامه تمدید کن"
        "bound to another device" -> "این اشتراک روی دستگاه دیگری فعال است — برای انتقال با پشتیبانی تماس بگیر"
        "wrong username or password" -> "نام کاربری یا رمز عبور اشتباه است"
        "too many attempts, try later" -> "تلاش‌های زیاد — یک ساعت دیگر دوباره امتحان کن"
        "invalid username or password" -> "نام کاربری یا رمز عبور نامعتبر است"
        "invalid install_id", "invalid token" -> "شناسهٔ دستگاه نامعتبر است — اپ را دوباره باز کن"
        else -> "خطا از سرور: $workerError"
    }

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build()
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val base = BeeConfig.LICENSE_URL.trimEnd('/')
        if (BeeConfig.LICENSE_URL.isEmpty()) throw LicenseError.BadUrl()
        val req = try {
            HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
        } catch (_: Exception) {
            throw LicenseError.BadUrl()
        }
        val resp: HttpResponse<String> = try {
            client.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw LicenseError.Network(e.message ?: "بدون جزئیات")
        }
        val obj = try { JSONObject(resp.body()) } catch (_: Exception) {
            throw LicenseError.Network("پاسخ غیر JSON از سرور (کد ${resp.statusCode()})")
        }
        if (obj.optBoolean("ok", false)) return obj
        throw LicenseError.Server(obj.optString("error", "خطای ناشناخته (کد ${resp.statusCode()})"))
    }

    /// POST /activate { code, install_id } — binds a code to this device and
    /// starts the subscription clock at first activation.
    fun activate(code: String, installId: String): JSONObject =
        post("/activate", JSONObject().put("code", code).put("install_id", installId))

    /// POST /login { username, password, install_id }.
    fun login(username: String, password: String, installId: String): JSONObject =
        post("/login", JSONObject().put("username", username).put("password", password).put("install_id", installId))

    /// POST /verify { token, install_id } — re-check revocation + expiry,
    /// returns a fresh token.
    fun verify(token: String, installId: String): JSONObject =
        post("/verify", JSONObject().put("token", token).put("install_id", installId))
}
