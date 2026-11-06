package com.msnguard.vpn

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Latin digits → Persian digits, for buyer-facing numbers. */
internal fun faDigitsOf(s: String): String =
    s.map { c -> if (c in '0'..'9') ('۰' + (c - '0')) else c }.joinToString("")

/** Days-left headline in the active language, Persian digits included. */
internal fun daysLeftSummary(days: Int): String =
    Strings.tf("%d days left", faDigitsOf(days.toString()))

// Iran has been UTC+3:30 year-round since 2022 (no DST) — the same offset
// the license server uses, so app and bot always print the same day.
private const val TEHRAN_OFFSET = 12600L

/**
 * The in-app subscription profile — "my hive pass".
 *
 * One screen that answers every question a buyer asks after paying: is my
 * subscription active, when exactly does it end (in the Persian calendar they
 * actually read), how many days are left, what did I buy, which device is it
 * bound to, and when did the server last bless this token.
 *
 * Data source is deliberately OFFLINE: [LicenseManager.subscriptionInfo] reads
 * the Ed25519-verified token already on the device (payload p = plan,
 * e = expiry) plus the license prefs. No endpoint, no network wait, no new
 * trust surface — the same numbers the VPN gate enforces are the numbers
 * shown here, so the profile can never disagree with the gate.
 *
 * Deliberately self-contained like [LicenseGateActivity]: its own palette
 * constants, its own view builders, no dependency on MainActivity's UI
 * plumbing. The colors mirror the gate's honey palette so gate, console and
 * profile read as one product. Opened from Settings → SUBSCRIPTION.
 */
class SubscriptionInfoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguage.appContext = applicationContext
        if (AppLanguage.current() == "fa") window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        setContentView(buildUi())
        LicenseManager.revalidateAsync(this)
    }

    private fun openUrl(url: String) {
        // Same rule as the gate: t.me links belong in the Telegram app.
        if (!TelegramOpener.open(this, url)) {
            Toast.makeText(this, Strings.t("No browser found"), Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------------- UI

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun pill(background: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(background)
            cornerRadius = dp(14).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun text(label: String, size: Float, color: Int, style: Int = Typeface.NORMAL): TextView =
        TextView(this).apply {
            this.text = label
            textSize = size
            setTextColor(color)
            setTypeface(Typefaces.regular(this@SubscriptionInfoActivity), style)
        }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = label
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(13), dp(16), dp(13))
            textSize = 15f
            setTypeface(Typefaces.semiBold(this@SubscriptionInfoActivity), if (filled) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (filled) HONEY_CANVAS else HONEY_PRIMARY)
            background = if (filled) pill(HONEY_PRIMARY) else pill(HONEY_SURFACE, HONEY_PRIMARY)
            setOnClickListener { onClick() }
        }

    /** One label/value line of the subscription card. */
    private fun infoRow(title: String, value: String, valueColor: Int = HONEY_INK): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = pill(HONEY_SURFACE, HONEY_DIVIDER)
            addView(text(Strings.t(title), 11.5f, HONEY_MUTED))
            addView(text(value, 14.5f, valueColor, Typeface.BOLD).apply { setPadding(0, dp(2), 0, 0) })
        }

    private fun infoRowList(vararg rows: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        for (r in rows) addView(r, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
    }

    private fun buildUi(): View {
        val info = LicenseManager.subscriptionInfo(this)
        val now = System.currentTimeMillis() / 1000

        val scroll = ScrollView(this).apply {
            setBackgroundColor(HONEY_CANVAS)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(40), dp(24), dp(32))
        }

        root.addView(text("🐝", 40f, HONEY_PRIMARY).apply { gravity = Gravity.CENTER })
        root.addView(text("BEE-VPN", 24f, HONEY_PRIMARY, Typeface.BOLD).apply {
            setTypeface(Typefaces.extraBold(this@SubscriptionInfoActivity), Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
        })
        root.addView(text(Strings.t("Subscription"), 15f, HONEY_INK).apply { setPadding(0, dp(2), 0, dp(16)) })

        if (!info.configured) {
            // Build without a license server: nothing to show but a note.
            root.addView(text(Strings.t("This build has no license server"), 14.5f, HONEY_MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(8))
            })
            scroll.addView(root)
            return scroll
        }

        // ---- hero card: status + days left + progress of the term ----
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = pill(HONEY_SURFACE, HONEY_DIVIDER)
        }
        val daysLeft = if (info.active) ((info.expiryEpochSec - now) / 86400).toInt() + 1 else 0
        when {
            info.active -> {
                hero.addView(text("✅ " + Strings.t("Subscription active"), 18f, HONEY_OK, Typeface.BOLD).apply {
                    setTypeface(Typefaces.semiBold(this@SubscriptionInfoActivity), Typeface.BOLD)
                })
                hero.addView(text(daysLeftSummary(daysLeft), 15f, HONEY_INK).apply { setPadding(0, dp(6), 0, 0) })
            }
            info.expired -> {
                hero.addView(text("❌ " + Strings.t("Subscription expired"), 18f, HONEY_DANGER, Typeface.BOLD).apply {
                    setTypeface(Typefaces.semiBold(this@SubscriptionInfoActivity), Typeface.BOLD)
                })
                hero.addView(text(Strings.t("🛍 Renew subscription"), 13.5f, HONEY_MUTED).apply { setPadding(0, dp(6), 0, 0) })
            }
            else -> {
                hero.addView(text("⚠️ " + Strings.t("No active subscription on this device"), 16.5f, HONEY_DANGER).apply {
                    setTypeface(Typefaces.semiBold(this@SubscriptionInfoActivity), Typeface.BOLD)
                    gravity = Gravity.CENTER
                })
            }
        }

        // Progress bar: how much of the current term has elapsed. Only when we
        // know both ends (a never-activated code has no clock yet). Weighted
        // LinearLayout, because a fill measured against a not-yet-laid-out
        // parent is always wrong at build time.
        if (info.expiryEpochSec > 0 && info.activatedAtEpochSec > 0 && info.expiryEpochSec > info.activatedAtEpochSec) {
            val total = info.expiryEpochSec - info.activatedAtEpochSec
            val elapsed = (now - info.activatedAtEpochSec).coerceIn(0, total)
            val frac = elapsed.toFloat() / total
            val fill = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(if (info.active) HONEY_PRIMARY else HONEY_DANGER)
                    cornerRadius = dp(4).toFloat()
                }
            }
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { setColor(HONEY_TRACK); cornerRadius = dp(4).toFloat() }
                clipToOutline = true
            }
            bar.addView(fill, LinearLayout.LayoutParams(0, dp(8), frac.coerceAtLeast(0.02f)))
            bar.addView(View(this), LinearLayout.LayoutParams(0, dp(8), (1 - frac).coerceAtLeast(0f)))
            hero.addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) })
        }
        root.addView(hero, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })

        // ---- the card rows ----
        val planLabel = when (info.plan) {
            "trial" -> Strings.t("Trial (1 day)")
            "m1" -> Strings.t("1 month")
            "m3" -> Strings.t("3 months")
            "m6" -> Strings.t("6 months")
            "m12" -> Strings.t("12 months")
            else -> "—"
        }
        val kindLabel = when (info.kind) {
            "code" -> Strings.t("Activation code")
            "account" -> Strings.t("Account")
            else -> "—"
        }
        val expiryLine = if (info.expiryEpochSec > 0)
            "${jalali(info.expiryEpochSec)}  (${gregorian(info.expiryEpochSec)})"
        else Strings.t("Never started yet — the clock begins at first activation")
        val startLine = if (info.activatedAtEpochSec > 0) jalali(info.activatedAtEpochSec)
        else Strings.t("Never started yet — the clock begins at first activation")
        val checkLine = if (info.lastCheckEpochSec > 0) jalali(info.lastCheckEpochSec) else Strings.t("Not verified yet")

        root.addView(infoRowList(
            infoRow("Status", if (info.active) Strings.t("Active") else Strings.t("Expired"),
                if (info.active) HONEY_OK else HONEY_DANGER),
            infoRow("Plan", planLabel),
            infoRow("Expiry date", expiryLine, if (info.active) HONEY_INK else HONEY_DANGER),
            infoRow("Term start", startLine),
            infoRow("Type", kindLabel),
            infoRow("ID", info.subject.ifBlank { "—" }),
            infoRow("Device", info.installId.take(12) + "…"),
            infoRow("Last verified", checkLine),
        ).apply { setPadding(0, dp(16), 0, 0) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        // ---- actions: renew (the sales bot) + support ----
        root.addView(button(Strings.t("🛍 Buy subscription"), true) { openUrl(BuildConfig.BEE_TELEGRAM_URL) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(20) })
        root.addView(button(Strings.t("🆘 Support"), false) { openUrl(BuildConfig.BEE_SUPPORT_URL) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) })

        root.addView(text(Strings.t(
            "Every subscription is bound to exactly one device. Changed your phone? Ask support for a reset.",
        ), 12f, HONEY_MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })

        scroll.addView(root)
        return scroll
    }

    // ------------------------------------------------------- dates & digits

    private fun utcDate(epochSec: Long): IntArray {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochSec * 1000 + TEHRAN_OFFSET * 1000
        return intArrayOf(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
    }

    private fun gregorian(epochSec: Long): String {
        val (y, m, d) = utcDate(epochSec)
        return faDigitsOf("%04d-%02d-%02d".format(y, m, d))
    }

    /** Gregorian → Jalali (the classic jdf.scr.ir algorithm, as on the server). */
    private fun jalali(epochSec: Long): String {
        var gy = utcDate(epochSec)
        val gDm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        var jy = if (gy[0] <= 1600) 0 else 979
        gy[0] -= if (gy[0] <= 1600) 621 else 1600
        val gy2 = if (gy[1] > 2) gy[0] + 1 else gy[0]
        var days = 365 * gy[0] + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400
        days += -80 + gDm[gy[1] - 1] + gy[2]
        jy += 33 * (days / 12053); days %= 12053
        jy += 4 * (days / 1461); days %= 1461
        jy += (days - 1) / 365
        if (days > 365) days = (days - 1) % 365
        val jm = if (days < 186) 1 + days / 31 else 7 + (days - 186) / 30
        val jd = 1 + (if (days < 186) days % 31 else (days - 186) % 30)
        return faDigitsOf("%04d/%02d/%02d".format(jy, jm, jd))
    }

    companion object {
        // Mirrors LicenseGateActivity's honey palette (AppAppearance.ORBIT).
        private val HONEY_CANVAS = Color.parseColor("#04070B")
        private val HONEY_SURFACE = Color.parseColor("#0B1116")
        private val HONEY_DIVIDER = Color.parseColor("#1C2429")
        private val HONEY_INK = Color.parseColor("#EAF6F3")
        private val HONEY_MUTED = Color.parseColor("#9DB0B5")
        private val HONEY_PRIMARY = Color.parseColor("#FFC107")
        private val HONEY_DANGER = Color.parseColor("#FF6B7F")
        private val HONEY_OK = Color.parseColor("#59D98E")
        private val HONEY_TRACK = Color.parseColor("#141C22")
    }
}
