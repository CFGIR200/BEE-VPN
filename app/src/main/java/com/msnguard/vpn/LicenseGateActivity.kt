package com.msnguard.vpn

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The paywall — "the hive is locked".
 *
 * Owns the first decision of every launch: does this device have a live
 * entitlement? If yes (or the build carries no license server at all), the
 * activity hands straight to [MainActivity] without drawing anything. If no,
 * this screen is the whole app: activate with a code, or sign in with the
 * account the operator issued, or follow one of the three links out
 * (trial code, purchase, support).
 *
 * Deliberately self-contained: its own palette constants, its own view
 * builders, no dependency on MainActivity's 10k-line UI plumbing. The colors
 * mirror AppAppearance.ORBIT (canvas #04070B, honey #FFC107) so the gate and
 * the console read as one product. When activation succeeds the stored token
 * is verified locally by [LicenseManager] — this screen never sees a secret.
 *
 * Expiry handling: MainActivity.onResume bounces an expired device back here
 * with EXTRA_REASON=expired; the status line then says so instead of the
 * generic hint, and everything else behaves identically.
 */
class LicenseGateActivity : Activity() {

    private lateinit var codePanel: LinearLayout
    private lateinit var accountPanel: LinearLayout
    private lateinit var codeTab: TextView
    private lateinit var accountTab: TextView
    private lateinit var codeField: EditText
    private lateinit var userField: EditText
    private lateinit var passField: EditText
    private lateinit var codeButton: TextView
    private lateinit var loginButton: TextView
    private lateinit var statusView: TextView

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguage.appContext = applicationContext
        // Entitled (or the build has no license server): straight through.
        if (!LicenseManager.configured() || LicenseManager.isEntitled(this)) {
            launchMain()
            return
        }
        if (AppLanguage.current() == "fa") window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        setContentView(buildUi())

        if (intent.getStringExtra(EXTRA_REASON) == "expired") {
            statusView.text = Strings.t("Subscription expired — renew to continue")
            statusView.setTextColor(HONEY_DANGER)
            statusView.visibility = View.VISIBLE
        }
        LicenseManager.revalidateAsync(this)
    }

    // ------------------------------------------------------------------- flow

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, Strings.t("No browser found"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        codeButton.isEnabled = !value
        loginButton.isEnabled = !value
        codeButton.alpha = if (value) 0.55f else 1f
        loginButton.alpha = if (value) 0.55f else 1f
    }

    private fun showStatus(text: String, error: Boolean) {
        statusView.text = text
        statusView.setTextColor(if (error) HONEY_DANGER else HONEY_MUTED)
        statusView.visibility = View.VISIBLE
    }

    // -------------------------------------------------------------------- UI

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun pill(background: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(background)
            cornerRadius = dp(14).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun field(hintText: String, password: Boolean): EditText = EditText(this).apply {
        hint = hintText
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else imeOptions = EditorInfo.IME_ACTION_NEXT
        setTextColor(HONEY_INK)
        setHintTextColor(HONEY_MUTED)
        background = pill(HONEY_SURFACE, HONEY_DIVIDER)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        textSize = 14f
        typeface = Typefaces.regular(this@LicenseGateActivity)
    }

    private fun button(text: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(13), dp(16), dp(13))
            textSize = 15f
            setTypeface(Typefaces.semiBold(this@LicenseGateActivity), if (filled) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (filled) HONEY_CANVAS else HONEY_PRIMARY)
            background = if (filled) pill(HONEY_PRIMARY) else pill(HONEY_SURFACE, HONEY_PRIMARY)
            setOnClickListener { if (!busy) onClick() }
        }

    private fun tab(text: String): TextView = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(10), dp(8), dp(10))
        textSize = 13.5f
        typeface = Typefaces.semiBold(this@LicenseGateActivity)
        setOnClickListener { if (!busy) selectTab(this) }
    }

    private fun link(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setPadding(dp(8), dp(14), dp(8), dp(14))
        textSize = 13.5f
        setTextColor(HONEY_MUTED)
        typeface = Typefaces.regular(this@LicenseGateActivity)
        setOnClickListener { if (!busy) onClick() }
    }

    /** Two-state tab switch: the selected tab gets the honey underline pill. */
    private fun selectTab(selected: TextView) {
        val isCode = selected === codeTab
        codeTab.background = pill(if (isCode) HONEY_PRIMARY else Color.TRANSPARENT)
        codeTab.setTextColor(if (isCode) HONEY_CANVAS else HONEY_MUTED)
        accountTab.background = pill(if (!isCode) HONEY_PRIMARY else Color.TRANSPARENT)
        accountTab.setTextColor(if (!isCode) HONEY_CANVAS else HONEY_MUTED)
        codePanel.visibility = if (isCode) View.VISIBLE else View.GONE
        accountPanel.visibility = if (isCode) View.GONE else View.VISIBLE
        statusView.visibility = View.GONE
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(HONEY_CANVAS)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "🐝"
            textSize = 42f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "BEE-VPN"
            textSize = 26f
            setTextColor(HONEY_PRIMARY)
            setTypeface(Typefaces.extraBold(this@LicenseGateActivity), Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
        })
        root.addView(TextView(this).apply {
            text = Strings.t("The hive is locked")
            textSize = 15f
            setTextColor(HONEY_INK)
            typeface = Typefaces.semiBold(this@LicenseGateActivity)
            setPadding(0, dp(4), 0, dp(2))
        })
        root.addView(TextView(this).apply {
            text = Strings.t("Activate your subscription to use BEE-VPN")
            textSize = 12.5f
            setTextColor(HONEY_MUTED)
            typeface = Typefaces.regular(this@LicenseGateActivity)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(20))
        })

        // Tabs: activation code | account
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(HONEY_SURFACE)
            background = pill(HONEY_SURFACE, HONEY_DIVIDER)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        codeTab = tab(Strings.t("Activation code"))
        accountTab = tab(Strings.t("Account"))
        tabs.addView(codeTab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabs.addView(accountTab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabs)

        statusView = TextView(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typefaces.regular(this@LicenseGateActivity)
            setPadding(dp(4), dp(14), dp(4), dp(0))
        }

        // Panel 1: code
        codePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, 0)
        }
        codeField = field(Strings.t("BEE-XXXX-XXXX-XXXX"), false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        codePanel.addView(codeField)
        codeButton = button(Strings.t("Activate"), true) { activate() }
        codePanel.addView(codeButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        // Panel 2: account
        accountPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, 0)
            visibility = View.GONE
        }
        userField = field(Strings.t("Username"), false)
        passField = field(Strings.t("Password"), true).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        accountPanel.addView(userField)
        accountPanel.addView(passField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        loginButton = button(Strings.t("Sign in to BEE-VPN"), true) { signIn() }
        accountPanel.addView(loginButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        root.addView(codePanel)
        root.addView(accountPanel)
        root.addView(statusView)

        // Outbound links: trial, purchase, support.
        val links = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, 0)
        }
        links.addView(link(Strings.t("🛍 Buy subscription"), { openUrl(BuildConfig.BEE_TELEGRAM_URL) }))
        links.addView(link(Strings.t("🎁 1-day free trial"), { openUrl(BuildConfig.BEE_TELEGRAM_URL) }))
        links.addView(link(Strings.t("🆘 Support"), { openUrl(BuildConfig.BEE_SUPPORT_URL) }))
        root.addView(links)

        scroll.addView(root)

        selectTab(codeTab)
        return scroll
    }

    // ---------------------------------------------------------------- actions

    private fun activate() {
        val code = codeField.text.toString().trim()
        if (code.isEmpty()) {
            showStatus(Strings.t("Invalid activation code"), true)
            return
        }
        setBusy(true)
        showStatus(Strings.t("Verifying…"), false)
        LicenseManager.activateWithCode(this, code) { ok, message ->
            if (ok) {
                showStatus(message, false)
                launchMain()
            } else {
                setBusy(false)
                showStatus(message, true)
            }
        }
    }

    private fun signIn() {
        val username = userField.text.toString().trim()
        val password = passField.text.toString()
        if (username.isEmpty() || password.isEmpty()) {
            showStatus(Strings.t("Wrong username or password"), true)
            return
        }
        setBusy(true)
        showStatus(Strings.t("Signing in…"), false)
        LicenseManager.login(this, username, password) { ok, message ->
            if (ok) {
                showStatus(message, false)
                launchMain()
            } else {
                setBusy(false)
                showStatus(message, true)
            }
        }
    }

    companion object {
        /** Set when MainActivity bounces an expired device back to the gate. */
        const val EXTRA_REASON = "reason"

        // Mirrors AppAppearance.ORBIT so the gate reads as the same product
        // without coupling to that palette's plumbing.
        private val HONEY_CANVAS = Color.parseColor("#04070B")
        private val HONEY_SURFACE = Color.parseColor("#0B1116")
        private val HONEY_DIVIDER = Color.parseColor("#1C2429")
        private val HONEY_INK = Color.parseColor("#EAF6F3")
        private val HONEY_MUTED = Color.parseColor("#9DB0B5")
        private val HONEY_PRIMARY = Color.parseColor("#FFC107")
        private val HONEY_DANGER = Color.parseColor("#FF6B7F")
    }
}
