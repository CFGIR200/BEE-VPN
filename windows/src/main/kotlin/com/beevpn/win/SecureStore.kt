package com.beevpn.win

import java.io.File

import org.json.JSONObject

/// The device-local secret store — the Windows stand-in for the Android
/// SharedPreferences gate file and the iOS Keychain.
///
/// Values are stored base64-obfuscated (never plain) inside
/// %APPDATA%/BEE-VPN/store.json, which sits under the user's DPAPI-protected
/// profile — the same trust level the Android app's private filesDir gets from
/// sandboxing. Atomic writes; one JSON root, keyed like the iOS Keychain:
/// "license.token", "license.subject", "warp.identity", …
object SecureStore {

    private val lock = Any()

    fun put(key: String, value: String?) {
        synchronized(lock) {
            val root = read()
            if (value == null) root.remove(key) else root.put(key, b64(value))
            write(root)
        }
    }

    fun get(key: String): String? {
        synchronized(lock) {
            val raw = read().optString(key, "")
            return if (raw.isEmpty()) null else unb64(raw)
        }
    }

    fun remove(key: String) = put(key, null)

    private fun read(): JSONObject {
        val f = AppPaths.storeFile
        if (!f.isFile) return JSONObject()
        return try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() }
    }

    private fun write(root: JSONObject) {
        val f = AppPaths.storeFile
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(root.toString(2))
        if (tmp.renameTo(f)) return
        f.delete()
        tmp.renameTo(f)
    }

    private fun b64(s: String): String =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun unb64(s: String): String? = try {
        String(java.util.Base64.getDecoder().decode(s), Charsets.UTF_8)
    } catch (_: Exception) { null }
}
