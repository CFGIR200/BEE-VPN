package com.msnguard.vpn

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens t.me links inside Telegram itself — the main Telegram app first —
 * never in a browser-first chooser.
 *
 * Why this exists: a plain `ACTION_VIEW https://t.me/...` relies on Android
 * App Links verification, and verification requires Google Play Services to
 * be alive at install time. Most Iranian phones ship without working Play
 * Services (or with it neutered by sanctions), so the verification never
 * happens and the link lands in Chrome — where the sales bot renders as a
 * "view in Telegram" wall and the purchase dies. The ladder below fixes the
 * routing explicitly:
 *
 *   1. Official Telegram by package name — no chooser, straight into the app
 *      the user means when they say "Telegram".
 *   2. Any other Telegram-family client through the `tg://` scheme — Plus,
 *      Telegraph, Nicegram and friends all register it. Better than the
 *      browser for people whose only Telegram is a fork.
 *   3. Browser — only when no Telegram exists on the device at all.
 *
 * Non-Telegram URLs fall through to step 3 unchanged, so callers can hand
 * this every link they have.
 */
object TelegramOpener {

    /** Official clients only, in preference order. Forks are deliberately NOT
     *  listed: they are reached through the tg:// fallback instead, so a user
     *  with both official Telegram and a fork always gets the official one. */
    private val OFFICIAL_PACKAGES = listOf(
        "org.telegram.messenger",      // Telegram — the main app
        "org.telegram.messenger.beta", // Telegram beta
        "org.thunderdog.challegram",   // Telegram X
    )

    /** Opens [url] and returns true when something handled it. */
    fun open(context: Context, url: String): Boolean {
        val link = url.trim()
        if (isTelegramLink(link)) {
            // 1) The official Telegram app, by explicit package — no chooser.
            for (pkg in OFFICIAL_PACKAGES) {
                if (tryStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg))) return true
            }
            // 2) Fork clients own the tg:// scheme; deep-link to the same
            //    target through whichever of them is installed.
            if (tryStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(toTgScheme(link))))) return true
        }
        // 3) Not a Telegram link, or no Telegram client at all: browser.
        return tryStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(link)))
    }

    fun isTelegramLink(url: String): Boolean =
        url.contains("t.me/") || url.contains("telegram.me/")

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    /** https://t.me/Bot?start=buy -> tg://resolve?domain=Bot&start=buy */
    private fun toTgScheme(url: String): String {
        val m = Regex("^https?://(?:t|telegram)\\.me/(.+)$").find(url) ?: return url
        val parts = m.groupValues[1].split("?", limit = 2)
        return "tg://resolve?domain=" + parts[0] +
            (if (parts.size == 2) "&" + parts[1] else "")
    }
}
