package com.enigma.farsilandtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.PasswordTransformationMethod
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

internal fun VpnSession.actionLabel(context: Context): String = context.getString(when (phase) {
    VpnPhase.ON -> R.string.vpn_disconnect
    VpnPhase.CONNECTING -> R.string.vpn_cancel
    VpnPhase.STOPPING -> R.string.vpn_stopping
    VpnPhase.OFF, VpnPhase.ERROR -> R.string.vpn_connect
})

internal fun VpnSession.statusLabel(context: Context): String = context.getString(when {
    phase == VpnPhase.ON -> R.string.vpn_connected
    phase == VpnPhase.CONNECTING -> R.string.vpn_connecting
    phase == VpnPhase.STOPPING -> R.string.vpn_stopping
    failure == VpnFailure.SETUP -> R.string.vpn_setup_missing
    failure == VpnFailure.SUBSCRIPTION -> R.string.vpn_subscription_failed
    failure == VpnFailure.NETWORK -> R.string.vpn_network_failed
    failure == VpnFailure.ENGINE -> R.string.vpn_engine_failed
    failure == VpnFailure.DROPPED -> R.string.vpn_dropped
    failure == VpnFailure.CONSENT -> R.string.vpn_consent_needed
    else -> R.string.vpn_off
})

/**
 * VPN screen (1.34): a status card in the app's theme (shield, a large state word, details and one large
 * Connect button) beside cards for adding a VPN: a free FreeSocks key, Google Drive, the private phone import
 * and a subscription link.
 */
class VpnSetupActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var secrets: VpnSecrets
    private lateinit var tone: AppThemes.Palette
    private lateinit var shield: ImageView
    private lateinit var state: TextView
    private lateinit var source: TextView
    private lateinit var status: TextView
    private lateinit var field: EditText
    private lateinit var connect: Button
    private lateinit var save: Button
    private lateinit var freeSocksCard: Button
    private lateinit var importDrive: Button
    private lateinit var importLocal: Button
    private lateinit var forget: Button
    private var busy = false
    private var connectAfterConsent = false
    private val observer: () -> Unit = { update() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectAfterConsent = savedInstanceState?.getBoolean("connectAfterConsent") ?: false
        if (!resources.getBoolean(R.bool.amin_allow_screenshots)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        secrets = VpnSecrets(this)
        tone = AppThemes.palette(AppThemes.forSaved(getSharedPreferences("playback_settings", MODE_PRIVATE)
            .getString("theme", null)))
        window.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(mix(tone.accent, tone.bg, 0.16f), tone.bg, tone.bg)))

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(48), dp(28), dp(48), dp(24))
        }

        // Status card (right side in RTL).
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(22), dp(24), dp(22))
            background = rounded(tone.panel, dp(22).toFloat(), stroke = mix(tone.text, tone.panel, 0.12f))
        }
        shield = ImageView(this).apply { setImageResource(R.drawable.ic_vpn_shield) }
        hero.addView(shield, LinearLayout.LayoutParams(dp(84), dp(84)))
        hero.addView(label(getString(R.string.vpn_title), 20f, tone.muted, bold = true).apply { gravity = Gravity.CENTER })
        state = label("", 34f, tone.text, bold = true).apply { gravity = Gravity.CENTER }
        hero.addView(state)
        source = label("", 15f, tone.muted).apply { gravity = Gravity.CENTER }
        hero.addView(source)
        status = label("", 15f, tone.text).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(10), 0, dp(14))
        }
        hero.addView(status, LinearLayout.LayoutParams(-1, 0, 1f))
        connect = button(R.string.vpn_connect, primary = true) { startOrStop() }
        hero.addView(connect, LinearLayout.LayoutParams(-1, dp(60)))
        page.addView(hero, LinearLayout.LayoutParams(0, -1, 0.42f).apply { marginEnd = dp(24) })

        // Setup cards (left side in RTL).
        val cards = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        cards.addView(label(getString(R.string.vpn_add_title), 19f, tone.muted, bold = true).apply { setPadding(dp(4), 0, dp(4), dp(6)) })
        freeSocksCard = card(cards, R.drawable.ic_vpn_key, R.string.vpn_freesocks_title, R.string.vpn_freesocks_sub) { showFreeSocks() }
        importDrive = card(cards, R.drawable.ic_google_drive, R.string.vpn_import_drive, R.string.vpn_drive_sub) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, LocalVpnImportActivity::class.java)
                .putExtra(LocalVpnImportActivity.DRIVE_IMPORT, true), LOCAL_IMPORT)
        }
        importLocal = card(cards, R.drawable.ic_vpn_phone, R.string.vpn_local_import, R.string.vpn_local_sub) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, LocalVpnImportActivity::class.java), LOCAL_IMPORT)
        }
        cards.addView(label(getString(R.string.vpn_link_title), 15f, tone.muted).apply { setPadding(dp(4), dp(10), dp(4), dp(2)) })
        val linkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        field = EditText(this).apply {
            hint = "https://…"
            contentDescription = getString(R.string.vpn_subscription_label)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textDirection = View.TEXT_DIRECTION_LTR
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setSingleLine(true)
            transformationMethod = PasswordTransformationMethod.getInstance()
            isSaveEnabled = false
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
            setTextColor(tone.text)
            setHintTextColor(tone.muted)
            setPadding(dp(16), 0, dp(16), 0)
            background = focusable(tone.panel, tone.panel)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { saveAddress(text.toString()); true } else false
            }
        }
        linkRow.addView(field, LinearLayout.LayoutParams(0, dp(52), 1f))
        save = button(R.string.vpn_save) { saveAddress(field.text.toString()) }
        linkRow.addView(save, LinearLayout.LayoutParams(-2, dp(52)).apply { marginStart = dp(10) })
        cards.addView(linkRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        forget = button(R.string.vpn_forget) {
            secrets.clear()
            VpnStatus.stop()
            update()
        }
        footer.addView(forget, LinearLayout.LayoutParams(0, dp(50), 1f))
        footer.addView(button(R.string.vpn_back) { finish() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(10) })
        cards.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        cards.addView(label(getString(R.string.vpn_privacy_hint), 13f, tone.muted).apply { setPadding(dp(4), dp(10), dp(4), 0) })
        val scroll = ScrollView(this).apply { addView(cards); isFillViewport = true; isVerticalScrollBarEnabled = false }
        page.addView(scroll, LinearLayout.LayoutParams(0, -1, 0.58f))
        setContentView(FrameLayout(this).apply { addView(page, FrameLayout.LayoutParams(-1, -1)) })

        update()
        if (secrets.configured()) connect.requestFocus() else freeSocksCard.requestFocus()
        if (savedInstanceState == null && intent.getBooleanExtra(CONNECT_REQUEST, false) &&
            VpnStatus.current.phase in setOf(VpnPhase.OFF, VpnPhase.ERROR)) {
            explainConsent()
        }
    }

    // ---- look ----
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    /** amount of [a] blended into [b]. */
    private fun mix(a: Int, b: Int, amount: Float): Int {
        fun ch(shift: Int) = ((((a shr shift) and 0xff) * amount) + (((b shr shift) and 0xff) * (1 - amount))).toInt()
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null, width: Int = 2) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke != null) setStroke(dp(width), stroke)
    }

    /** Raised surface normally; theme focus colour with a ring when the remote is on it. */
    private fun focusable(normal: Int, normalHi: Int, primary: Boolean = false) = StateListDrawable().apply {
        val radius = dp(18).toFloat()
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(tone.focus, tone.accent)).apply { cornerRadius = radius; setStroke(dp(3), Color.WHITE) })
        addState(intArrayOf(-android.R.attr.state_enabled), rounded(mix(normal, tone.bg, 0.5f), radius))
        addState(intArrayOf(), if (primary) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(tone.accent, mix(tone.accent, tone.raised, 0.7f))).apply { cornerRadius = radius }
        else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(normalHi, normal)).apply {
            cornerRadius = radius
            setStroke(dp(1), mix(tone.text, normal, 0.1f))
        })
    }

    private fun textColors(primary: Boolean = false) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(tone.onFocus, mix(tone.text, tone.bg, 0.45f), if (primary) tone.onFocus else tone.text))

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(text: Int, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        setText(text)
        textSize = if (primary) 21f else 17f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        background = focusable(tone.raised, tone.raisedHi, primary)
        setTextColor(textColors(primary))
        setPadding(dp(18), 0, dp(18), 0)
        setOnClickListener { action() }
    }

    /** A large card: icon, bold title and a quieter line underneath. */
    private fun card(parent: LinearLayout, icon: Int, title: Int, sub: Int, action: () -> Unit): Button = Button(this).apply {
        val heading = getString(title)
        val detail = getString(sub)
        text = SpannableStringBuilder(heading).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, heading.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("\n")
            val start = length
            append(detail)
            setSpan(AbsoluteSizeSpan(14, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textSize = 18f
        isAllCaps = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        stateListAnimator = null
        background = focusable(tone.raised, tone.raisedHi)
        setTextColor(textColors())
        setPadding(dp(18), dp(8), dp(18), dp(8))
        compoundDrawablePadding = dp(14)
        val drawable = getDrawable(icon)?.mutate()
        if (icon != R.drawable.ic_google_drive) drawable?.setTintList(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(tone.onFocus, tone.accent)))
        drawable?.setBounds(0, 0, dp(30), dp(30))
        setCompoundDrawablesRelative(drawable, null, null, null)
        setOnClickListener { action() }
    }.also { parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }

    // ---- state ----
    private fun update() {
        if (isDestroyed) return
        val session = VpnStatus.current
        val ready = secrets.configured()
        connect.text = session.actionLabel(this)
        connect.isEnabled = !busy && session.phase != VpnPhase.STOPPING && (ready || session.phase != VpnPhase.OFF)
        val (word, tint) = when (session.phase) {
            VpnPhase.ON -> R.string.vpn_state_on to Color.rgb(57, 212, 131)
            VpnPhase.CONNECTING -> R.string.vpn_state_connecting to tone.accent
            VpnPhase.STOPPING -> R.string.vpn_state_stopping to tone.accent
            VpnPhase.ERROR -> R.string.vpn_state_error to Color.rgb(255, 122, 107)
            VpnPhase.OFF -> R.string.vpn_state_off to tone.muted
        }
        state.setText(word)
        state.setTextColor(tint)
        shield.setColorFilter(tint)
        source.setText(if (ready) R.string.vpn_source_personal else R.string.vpn_source_none)
        status.text = when {
            busy -> getString(R.string.vpn_saving)
            session.phase == VpnPhase.OFF && ready -> getString(R.string.vpn_saved)
            session.phase == VpnPhase.OFF -> getString(R.string.vpn_setup_missing)
            else -> session.statusLabel(this)
        }
        val editable = !busy && session.phase in setOf(VpnPhase.OFF, VpnPhase.ERROR)
        listOf(field, save, freeSocksCard, importDrive, importLocal).forEach { it.isEnabled = editable }
        forget.isEnabled = editable && secrets.configured()
    }

    private fun showFreeSocks() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        box.addView(label(getString(R.string.vpn_freesocks_steps), 17f, Color.WHITE).apply { setLineSpacing(0f, 1.25f) },
            LinearLayout.LayoutParams(0, -2, 1f))
        runCatching {
            val matrix = LocalImportQr.matrix(FREESOCKS, dp(200))
            val pixels = IntArray(matrix.width * matrix.height) { i ->
                if (matrix[i % matrix.width, i / matrix.width]) Color.BLACK else Color.WHITE
            }
            Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        }.getOrNull()?.let { qr ->
            box.addView(ImageView(this).apply { setImageBitmap(qr); contentDescription = FREESOCKS },
                LinearLayout.LayoutParams(dp(200), dp(200)).apply { marginStart = dp(20) })
        }
        AlertDialog.Builder(this).setTitle(R.string.vpn_freesocks_title).setView(box)
            .setPositiveButton(R.string.vpn_close, null).show()
    }

    private fun saveAddress(value: String) {
        if (busy || VpnStatus.current.phase !in setOf(VpnPhase.OFF, VpnPhase.ERROR)) return
        field.text.clear()
        if (runCatching { VpnImport.text(value) }.isFailure) {
            status.setText(R.string.vpn_invalid_url)
            return
        }
        busy = true
        update()
        worker.execute {
            val saved = runCatching { secrets.save(value) }.isSuccess
            runOnUiThread {
                busy = false
                if (!isDestroyed) {
                    if (saved) { VpnStatus.stop(); update(); connect.requestFocus() } else { update(); status.setText(R.string.vpn_save_failed) }
                }
            }
        }
    }

    private fun startOrStop() {
        when (VpnStatus.current.phase) {
            VpnPhase.ON, VpnPhase.CONNECTING -> AminVpnService.disconnect(this)
            VpnPhase.STOPPING -> Unit
            VpnPhase.OFF, VpnPhase.ERROR -> explainConsent()
        }
    }

    private fun explainConsent() {
        connectAfterConsent = secrets.configured()
        AlertDialog.Builder(this).setTitle(R.string.vpn_connect)
            .setMessage(R.string.vpn_consent_explanation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.vpn_continue) { _, _ ->
                val consent = VpnService.prepare(this)
                if (consent == null) completeVpnConsent()
                else {
                    @Suppress("DEPRECATION")
                    startActivityForResult(consent, VPN_CONSENT)
                }
            }.show()
    }

    private fun completeVpnConsent() {
        if (connectAfterConsent) AminVpnService.connect(this)
        else { status.setText(R.string.vpn_permission_ready); importLocal.requestFocus() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("connectAfterConsent", connectAfterConsent)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Activity callback remains supported on Android TV 11")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LOCAL_IMPORT) {
            update()
            if (resultCode == RESULT_OK) connect.requestFocus() else importLocal.requestFocus()
        } else if (requestCode == VPN_CONSENT) {
            if (resultCode == RESULT_OK) completeVpnConsent()
            else status.setText(R.string.vpn_consent_needed)
        }
    }

    override fun onStart() { super.onStart(); VpnStatus.observe(observer); update() }
    override fun onStop() { VpnStatus.remove(observer); super.onStop() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    companion object {
        const val CONNECT_REQUEST = "connect-vpn"
        private const val VPN_CONSENT = 801
        private const val LOCAL_IMPORT = 804
        private const val FREESOCKS = "https://freesocks.org/"
    }
}
