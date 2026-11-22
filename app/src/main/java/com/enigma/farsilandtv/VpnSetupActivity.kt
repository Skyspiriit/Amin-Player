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
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
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
import android.widget.Toast
import java.util.concurrent.Executors

internal fun VpnSession.actionLabel(context: Context): String = context.getString(when (phase) {
    VpnPhase.ON -> R.string.vpn_disconnect
    VpnPhase.CONNECTING -> R.string.vpn_cancel
    VpnPhase.STOPPING -> R.string.vpn_stopping
    VpnPhase.OFF, VpnPhase.ERROR -> R.string.vpn_connect
})

/** The power button's word: «VPN قطع», «در حال اتصال», «VPN وصل» or «در حال قطع». */
internal fun VpnSession.powerLabel(context: Context): String = context.getString(when (phase) {
    VpnPhase.ON -> R.string.vpn_power_on
    VpnPhase.CONNECTING -> R.string.vpn_power_connecting
    VpnPhase.STOPPING -> R.string.vpn_power_stopping
    VpnPhase.OFF, VpnPhase.ERROR -> R.string.vpn_power_off
})

internal fun VpnFailure.message(context: Context): String = context.getString(when (this) {
    VpnFailure.SETUP -> R.string.vpn_setup_missing
    VpnFailure.SUBSCRIPTION -> R.string.vpn_subscription_failed
    VpnFailure.NETWORK -> R.string.vpn_network_failed
    VpnFailure.ENGINE -> R.string.vpn_engine_failed
    VpnFailure.DROPPED -> R.string.vpn_dropped
    VpnFailure.CONSENT -> R.string.vpn_consent_needed
    VpnFailure.SITE -> R.string.vpn_site_failed
    VpnFailure.PLAYER -> R.string.vpn_player_failed
})

internal fun VpnFailure.short(context: Context): String = context.getString(when (this) {
    VpnFailure.SETUP -> R.string.vpn_reason_setup
    VpnFailure.SUBSCRIPTION -> R.string.vpn_reason_subscription
    VpnFailure.NETWORK -> R.string.vpn_reason_network
    VpnFailure.ENGINE -> R.string.vpn_reason_engine
    VpnFailure.DROPPED -> R.string.vpn_reason_dropped
    VpnFailure.CONSENT -> R.string.vpn_reason_consent
    VpnFailure.SITE -> R.string.vpn_reason_site
    VpnFailure.PLAYER -> R.string.vpn_reason_player
})

internal fun VpnSession.statusLabel(context: Context): String = when {
    phase == VpnPhase.ON && check == VpnCheck.PLAYBACK -> context.getString(R.string.vpn_check_playback)
    phase == VpnPhase.ON -> context.getString(R.string.vpn_connected)
    phase == VpnPhase.CONNECTING && recovering -> context.getString(R.string.vpn_reconnecting)
    phase == VpnPhase.CONNECTING -> context.getString(R.string.vpn_connecting)
    phase == VpnPhase.STOPPING -> context.getString(R.string.vpn_stopping)
    failure != null -> failure.message(context)
    else -> context.getString(R.string.vpn_off)
}

/**
 * VPN settings (1.35). Only configuration lives here: separate sections for personal V2Ray/Xray, personal Kerio
 * (IKEv2), free methods and Psiphon, each listing this device's own profiles. Connecting and disconnecting happen
 * only with the power button on the home screen, so opening or closing this screen never changes the connection.
 */
class VpnSetupActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: VpnProfileStore
    private lateinit var tone: AppThemes.Palette
    private lateinit var shield: ImageView
    private lateinit var state: TextView
    private lateinit var source: TextView
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var sections: LinearLayout
    private var profiles: List<VpnProfile> = emptyList()
    private var busy = false
    private var focusAfter: String? = null
    private var lastPhase: VpnSession? = null
    private val observer: () -> Unit = {
        // Redraw the rows only when something they show changed (a profile came into use or stopped).
        val now = VpnStatus.current
        val rows = lastPhase?.let { it.phase != now.phase || it.profileId != now.profileId } ?: true
        lastPhase = now
        if (rows) rebuild() else update()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!resources.getBoolean(R.bool.amin_allow_screenshots)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = VpnProfileStore(this)
        tone = AppThemes.palette(AppThemes.forSaved(getSharedPreferences("playback_settings", MODE_PRIVATE)
            .getString("theme", null)))
        window.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(mix(tone.accent, tone.bg, 0.16f), tone.bg, tone.bg)))

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(44), dp(26), dp(44), dp(22))
        }

        // Status card (right side in RTL). It only shows the state; it has no Connect button.
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = rounded(tone.panel, dp(22).toFloat(), stroke = mix(tone.text, tone.panel, 0.12f))
        }
        shield = ImageView(this).apply { setImageResource(R.drawable.ic_vpn_shield) }
        hero.addView(shield, LinearLayout.LayoutParams(dp(76), dp(76)))
        hero.addView(label(getString(R.string.vpn_settings), 20f, tone.muted, bold = true).apply { gravity = Gravity.CENTER })
        state = label("", 30f, tone.text, bold = true).apply { gravity = Gravity.CENTER }
        hero.addView(state)
        source = label("", 15f, tone.muted).apply { gravity = Gravity.CENTER }
        hero.addView(source)
        status = label("", 15f, tone.text).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(8), 0, dp(8))
        }
        hero.addView(status)
        count = label("", 14f, tone.muted).apply { gravity = Gravity.CENTER }
        hero.addView(count)
        hero.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        hero.addView(label(getString(R.string.vpn_settings_hint), 14f, tone.muted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        })
        page.addView(hero, LinearLayout.LayoutParams(0, -1, 0.34f).apply { marginEnd = dp(22) })

        sections = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val scroll = ScrollView(this).apply { addView(sections); isFillViewport = true; isVerticalScrollBarEnabled = false }
        page.addView(scroll, LinearLayout.LayoutParams(0, -1, 0.66f))
        setContentView(FrameLayout(this).apply { addView(page, FrameLayout.LayoutParams(-1, -1)) })
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

    private fun button(text: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = if (primary) 20f else 17f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        background = focusable(tone.raised, tone.raisedHi, primary)
        setTextColor(textColors(primary))
        setPadding(dp(18), 0, dp(18), 0)
        setOnClickListener { action() }
    }

    /** A large card: icon, bold title and a quieter line underneath. [dim] marks disabled or unavailable items. */
    private fun card(parent: LinearLayout, icon: Int?, heading: String, detail: String, dim: Boolean = false,
                     action: () -> Unit): Button = Button(this).apply {
        text = SpannableStringBuilder(heading).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, heading.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (detail.isNotEmpty()) {
                append("\n")
                val start = length
                append(detail)
                setSpan(AbsoluteSizeSpan(14, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        textSize = 17f
        isAllCaps = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        stateListAnimator = null
        background = focusable(if (dim) mix(tone.raised, tone.bg, 0.45f) else tone.raised,
            if (dim) mix(tone.raisedHi, tone.bg, 0.45f) else tone.raisedHi)
        setTextColor(if (dim) ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(tone.onFocus, tone.muted)) else textColors())
        setPadding(dp(18), dp(8), dp(18), dp(8))
        compoundDrawablePadding = dp(14)
        if (icon != null) {
            val drawable = getDrawable(icon)?.mutate()
            if (icon != R.drawable.ic_google_drive) drawable?.setTintList(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(tone.onFocus, if (dim) tone.muted else tone.accent)))
            drawable?.setBounds(0, 0, dp(28), dp(28))
            setCompoundDrawablesRelative(drawable, null, null, null)
        }
        setOnClickListener { action() }
    }.also { parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) }) }

    /** Section heading with a status badge (ready / not set up / disabled / unsupported / unavailable). */
    private fun heading(title: String, badge: String, badgeColor: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(16), dp(4), dp(2))
        }
        row.addView(label(title, 19f, tone.text, bold = true))
        row.addView(label(badge, 13f, badgeColor, bold = true).apply {
            setPadding(dp(10), dp(2), dp(10), dp(2))
            background = rounded(mix(badgeColor, tone.bg, 0.18f), dp(10).toFloat())
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        sections.addView(row)
    }

    private fun badge(group: List<VpnProfile>): Pair<String, Int> {
        val ready = group.count { it.enabled }
        return when {
            group.isEmpty() -> getString(R.string.vpn_badge_none) to tone.muted
            ready == 0 -> getString(R.string.vpn_badge_off) to tone.muted
            else -> getString(R.string.vpn_badge_ready, ready) to GREEN
        }
    }

    // ---- content ----
    private fun rebuild() {
        if (isDestroyed) return
        val focused = focusAfter ?: (currentFocus?.tag as? String)
        focusAfter = null
        profiles = runCatching { store.list() }.getOrDefault(emptyList())
        sections.removeAllViews()
        val tags = mutableMapOf<String, View>()

        val xray = profiles.filter { it.kind == VpnKind.XRAY && !it.free }
        badge(xray).let { (text, color) -> heading(getString(R.string.vpn_section_xray), text, color) }
        xray.forEach { tags[it.id] = profileRow(it) }
        tags["add-xray-phone"] = card(sections, R.drawable.ic_vpn_phone, getString(R.string.vpn_xray_phone),
            getString(R.string.vpn_xray_phone_sub)) { openImport(free = false) }
        tags["add-xray-drive"] = card(sections, R.drawable.ic_google_drive, getString(R.string.vpn_import_drive),
            getString(R.string.vpn_drive_sub)) { openImport(free = false, drive = true) }
        tags["add-xray-link"] = linkRow(free = false)

        val kerio = profiles.filter { it.kind == VpnKind.IKEV2 }
        if (PlatformVpn.supported) {
            badge(kerio).let { (text, color) -> heading(getString(R.string.vpn_section_kerio), text, color) }
            kerio.forEach { tags[it.id] = profileRow(it) }
            tags["add-kerio"] = card(sections, R.drawable.ic_vpn_key, getString(R.string.vpn_kerio_add),
                getString(R.string.vpn_kerio_add_sub)) { kerioForm(null) }
        } else {
            heading(getString(R.string.vpn_section_kerio), getString(R.string.vpn_badge_unsupported), tone.muted)
            tags["kerio-unsupported"] = card(sections, R.drawable.ic_vpn_key, getString(R.string.vpn_badge_unsupported),
                getString(R.string.vpn_kerio_unsupported), dim = true) {
                info(R.string.vpn_section_kerio, getString(R.string.vpn_kerio_unsupported))
            }
        }

        val free = profiles.filter { it.free }
        badge(free).let { (text, color) -> heading(getString(R.string.vpn_section_free), text, color) }
        free.forEach { tags[it.id] = profileRow(it) }
        tags["freesocks"] = card(sections, R.drawable.ic_vpn_key, getString(R.string.vpn_freesocks_title),
            getString(R.string.vpn_freesocks_sub)) { showFreeSocks() }
        tags["add-free-phone"] = card(sections, R.drawable.ic_vpn_phone, getString(R.string.vpn_free_phone),
            getString(R.string.vpn_free_phone_sub)) { openImport(free = true) }
        tags["add-free-link"] = linkRow(free = true)
        sections.addView(label(getString(R.string.vpn_free_note), 13f, tone.muted).apply { setPadding(dp(4), dp(6), dp(4), 0) })

        heading(getString(R.string.vpn_section_psiphon), getString(R.string.vpn_badge_unavailable), AMBER)
        tags["psiphon"] = card(sections, R.drawable.ic_vpn_shield, getString(R.string.vpn_badge_unavailable),
            getString(R.string.vpn_section_psiphon), dim = true) {
            info(R.string.vpn_section_psiphon, getString(R.string.vpn_psiphon_detail))
        }

        val back = button(getString(R.string.vpn_back)) { finish() }
        tags["back"] = back
        sections.addView(back, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) })
        sections.addView(label(getString(R.string.vpn_privacy_hint), 13f, tone.muted).apply { setPadding(dp(4), dp(10), dp(4), 0) })

        tags.forEach { (tag, view) -> view.tag = tag }
        val target = focused?.let(tags::get) ?: profiles.firstOrNull()?.let { tags[it.id] } ?: tags["add-xray-phone"]
        target?.requestFocus()
        update()
    }

    private fun profileRow(profile: VpnProfile): Button {
        val session = VpnStatus.current
        val inUse = session.profileId == profile.id && session.phase == VpnPhase.ON
        val parts = mutableListOf<String>()
        parts += getString(when {
            inUse -> R.string.vpn_profile_in_use
            profile.enabled -> R.string.vpn_profile_enabled
            else -> R.string.vpn_profile_disabled
        })
        if (profile.lastSuccess > 0) parts += getString(R.string.vpn_profile_last_ok, ago(profile.lastSuccess))
        if (profile.lastFailure != null && profile.lastTested > profile.lastSuccess) {
            parts += getString(R.string.vpn_profile_last_fail, profile.lastFailure.short(this))
        }
        if (profile.lastTested == 0L) parts += getString(R.string.vpn_profile_never)
        if (profile.playbackVerified > 0) parts += getString(R.string.vpn_profile_playback)
        if (profile.kind == VpnKind.IKEV2 &&
            runCatching { VpnProfileStore.ikev2(profile.secret).ca }.getOrDefault("").isNotEmpty()) {
            parts += getString(R.string.vpn_profile_cert)
        }
        return card(sections, if (profile.kind == VpnKind.IKEV2) R.drawable.ic_vpn_key else R.drawable.ic_vpn_shield,
            profile.name, parts.joinToString(" · "), dim = !profile.enabled) { profileActions(profile) }
    }

    private fun ago(time: Long) = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS).toString()

    private fun linkRow(free: Boolean): View {
        sections.addView(label(getString(if (free) R.string.vpn_free_link_title else R.string.vpn_link_title), 15f, tone.muted)
            .apply { setPadding(dp(4), dp(8), dp(4), dp(2)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val field = secretField(if (free) "vless://… / https://…" else "https://…", getString(R.string.vpn_subscription_label))
        row.addView(field, LinearLayout.LayoutParams(0, dp(52), 1f))
        val save = button(getString(R.string.vpn_save)) { saveLink(field, free) }
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { saveLink(field, free); true } else false
        }
        row.addView(save, LinearLayout.LayoutParams(-2, dp(52)).apply { marginStart = dp(10) })
        sections.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return field
    }

    private fun secretField(hint: String, description: String, secret: Boolean = true) = EditText(this).apply {
        this.hint = hint
        contentDescription = description
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI)
        textDirection = View.TEXT_DIRECTION_LTR
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setSingleLine(true)
        if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
        isSaveEnabled = false
        if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        setTextColor(tone.text)
        setHintTextColor(tone.muted)
        setPadding(dp(16), 0, dp(16), 0)
        background = focusable(tone.panel, tone.panel)
    }

    // ---- state ----
    private fun update() {
        if (isDestroyed) return
        val session = VpnStatus.current
        val (word, tint) = when (session.phase) {
            VpnPhase.ON -> R.string.vpn_power_on to GREEN
            VpnPhase.CONNECTING -> R.string.vpn_power_connecting to AMBER
            VpnPhase.STOPPING -> R.string.vpn_power_stopping to AMBER
            VpnPhase.ERROR, VpnPhase.OFF -> R.string.vpn_power_off to RED
        }
        state.setText(word)
        state.setTextColor(tint)
        shield.setColorFilter(tint)
        source.text = when {
            session.phase == VpnPhase.ON && session.profile != null -> getString(R.string.vpn_using, session.profile)
            session.phase == VpnPhase.CONNECTING && session.profile != null -> getString(R.string.vpn_trying, session.profile)
            profiles.isEmpty() -> getString(R.string.vpn_source_none)
            else -> ""
        }
        source.visibility = if (source.text.isEmpty()) View.GONE else View.VISIBLE
        status.text = when {
            busy -> getString(R.string.vpn_saving)
            session.phase == VpnPhase.OFF -> ""
            else -> session.statusLabel(this)
        }
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        count.text = getString(R.string.vpn_profiles_count, profiles.size)
    }

    private fun showStatus(text: Int) {
        status.setText(text)
        status.visibility = View.VISIBLE
    }

    private fun info(title: Int, text: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton(R.string.vpn_close, null).show()
    }

    private fun inUse(profile: VpnProfile) = VpnStatus.current.let {
        it.profileId == profile.id && it.phase in setOf(VpnPhase.ON, VpnPhase.CONNECTING, VpnPhase.STOPPING)
    }

    private fun profileActions(profile: VpnProfile) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(if (profile.enabled) R.string.vpn_profile_disable else R.string.vpn_profile_enable) to {
            if (inUse(profile)) info(R.string.vpn_settings, getString(R.string.vpn_profile_busy))
            else change(profile.id) { store.update(profile.id) { it.copy(enabled = !it.enabled) } }
        }
        if (profile.kind == VpnKind.IKEV2) {
            actions += getString(R.string.vpn_profile_edit) to {
                if (inUse(profile)) info(R.string.vpn_settings, getString(R.string.vpn_profile_busy)) else kerioForm(profile)
            }
            actions += getString(R.string.vpn_kerio_ca_import) to { openImport(free = false, certFor = profile.id) }
        } else {
            actions += getString(R.string.vpn_profile_rename) to { rename(profile) }
        }
        actions += getString(R.string.vpn_profile_delete) to {
            if (inUse(profile)) info(R.string.vpn_settings, getString(R.string.vpn_profile_busy)) else confirmDelete(profile)
        }
        AlertDialog.Builder(this).setTitle(profile.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.vpn_close, null).show()
    }

    private fun confirmDelete(profile: VpnProfile) {
        AlertDialog.Builder(this).setTitle(R.string.vpn_profile_delete)
            .setMessage(getString(R.string.vpn_profile_delete_confirm, profile.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.vpn_delete) { _, _ ->
                change(null) {
                    store.remove(profile.id)
                    if (profile.kind == VpnKind.IKEV2 && store.list().none { it.kind == VpnKind.IKEV2 }) PlatformVpn.forget(this)
                }
            }.create().apply {
                // Cancel is the default: deleting must be a deliberate second choice.
                setOnShowListener { getButton(AlertDialog.BUTTON_NEGATIVE).apply { isFocusableInTouchMode = true; requestFocus() } }
                show()
            }
    }

    private fun rename(profile: VpnProfile) {
        val field = secretField(profile.name, getString(R.string.vpn_profile_rename), secret = false).apply {
            textDirection = View.TEXT_DIRECTION_RTL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setText(profile.name)
        }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(field, FrameLayout.LayoutParams(-1, dp(52))) }
        AlertDialog.Builder(this).setTitle(R.string.vpn_profile_rename).setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.vpn_save) { _, _ ->
                val name = field.text.toString().trim().take(60)
                if (name.isNotEmpty()) change(profile.id) { store.update(profile.id) { it.copy(name = name) } }
            }.show()
    }

    /** Runs a store change off the main thread, then redraws with the focus on [focus]. */
    private fun change(focus: String?, action: () -> Unit) {
        busy = true
        update()
        worker.execute {
            val ok = runCatching(action).isSuccess
            runOnUiThread {
                busy = false
                if (isDestroyed) return@runOnUiThread
                focusAfter = focus
                rebuild()
                if (!ok) showStatus(R.string.vpn_save_failed)
            }
        }
    }

    private fun saveLink(field: EditText, free: Boolean) {
        val value = field.text.toString()
        field.text.clear()
        if (busy) return
        if (runCatching { VpnImport.text(value) }.isFailure) {
            showStatus(R.string.vpn_invalid_url)
            return
        }
        busy = true
        update()
        worker.execute {
            val saved = runCatching { addXray(this, value, free) }.getOrNull()
            runOnUiThread {
                busy = false
                if (isDestroyed) return@runOnUiThread
                focusAfter = saved?.id
                rebuild()
                if (saved == null) showStatus(R.string.vpn_save_failed)
            }
        }
    }

    private fun kerioForm(existing: VpnProfile?) {
        val old = existing?.let { runCatching { VpnProfileStore.ikev2(it.secret) }.getOrNull() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(4), dp(24), 0)
        }
        fun field(hint: Int, secret: Boolean, value: String?, ltr: Boolean = true) =
            secretField(getString(hint), getString(hint), secret).apply {
                if (!ltr) { textDirection = View.TEXT_DIRECTION_RTL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                if (value != null) setText(value)
                box.addView(this, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
            }
        val name = field(R.string.vpn_kerio_name, false, existing?.name, ltr = false)
        val server = field(R.string.vpn_kerio_server, false, old?.server)
        val user = field(R.string.vpn_kerio_user, false, old?.user)
        val password = field(R.string.vpn_kerio_password, true, old?.password)
        val psk = field(R.string.vpn_kerio_psk, true, old?.psk)
        box.addView(TextView(this).apply {
            setText(R.string.vpn_kerio_note)
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        })
        val dialog = AlertDialog.Builder(this).setTitle(R.string.vpn_section_kerio)
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.vpn_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val fields = Ikev2Fields(server.text.toString().trim(), user.text.toString().trim(),
                    password.text.toString(), psk.text.toString(), old?.ca.orEmpty())
                if (!fields.valid()) {
                    Toast.makeText(this, R.string.vpn_kerio_invalid, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val label = name.text.toString().trim().ifEmpty { getString(R.string.vpn_kerio_default) }
                dialog.dismiss()
                saveKerio(existing, label, fields)
            }
        }
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun saveKerio(existing: VpnProfile?, name: String, fields: Ikev2Fields) {
        busy = true
        update()
        worker.execute {
            val secret = VpnProfileStore.ikev2Secret(fields)
            val id = runCatching {
                if (existing == null) store.add(VpnKind.IKEV2, name, free = false, secret = secret).id
                else {
                    store.update(existing.id) { it.copy(name = name.take(60), secret = secret, lastFailure = null) }
                    existing.id
                }
            }.getOrNull()
            runOnUiThread {
                busy = false
                if (isDestroyed) return@runOnUiThread
                focusAfter = id
                rebuild()
                if (id == null) showStatus(R.string.vpn_save_failed) else askPlatformConsent(fields)
            }
        }
    }

    /** Android asks once whether this app may run its built-in VPN; asked here so Connect never prompts for it. */
    private fun askPlatformConsent(fields: Ikev2Fields) {
        if (Build.VERSION.SDK_INT < 30) return
        // Never touch Android's VPN profile while Android's own VPN may be the one in use.
        if (VpnStatus.current.phase != VpnPhase.OFF && VpnStatus.current.phase != VpnPhase.ERROR) return
        val consent = runCatching { PlatformVpn.provision(this, fields) }.getOrElse {
            showStatus(R.string.vpn_kerio_invalid)
            return
        } ?: return
        @Suppress("DEPRECATION")
        startActivityForResult(consent, PLATFORM_CONSENT)
    }

    private fun openImport(free: Boolean, drive: Boolean = false, certFor: String? = null) {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, LocalVpnImportActivity::class.java)
            .putExtra(LocalVpnImportActivity.DRIVE_IMPORT, drive)
            .putExtra(LocalVpnImportActivity.FREE, free)
            .putExtra(LocalVpnImportActivity.CERT_FOR, certFor), LOCAL_IMPORT)
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

    @Deprecated("Activity callback remains supported on Android TV 11")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LOCAL_IMPORT) {
            focusAfter = data?.getStringExtra(LocalVpnImportActivity.SAVED_ID)
            rebuild()
            if (resultCode == RESULT_OK && data?.getBooleanExtra(LocalVpnImportActivity.CERT_SAVED, false) == true) {
                showStatus(R.string.vpn_kerio_ca_saved)
            }
        } else if (requestCode == PLATFORM_CONSENT && resultCode != RESULT_OK) {
            showStatus(R.string.vpn_kerio_consent_failed)
        }
    }

    override fun onStart() {
        super.onStart()
        VpnStatus.observe(observer)
        lastPhase = VpnStatus.current
        rebuild()
    }
    override fun onStop() { VpnStatus.remove(observer); super.onStop() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    companion object {
        private const val LOCAL_IMPORT = 804
        private const val PLATFORM_CONSENT = 805
        private const val FREESOCKS = "https://freesocks.org/"
        val GREEN = Color.rgb(57, 212, 131)
        val AMBER = Color.rgb(242, 181, 68)
        val RED = Color.rgb(255, 106, 92)

        /** Validates and saves a V2Ray/Xray link, file or subscription as a new device-only profile. */
        internal fun addXray(context: Context, value: String, free: Boolean): VpnProfile {
            val text = VpnImport.text(value)
            if (!VpnImport.remote(text)) VpnEngine.nodes(text)
            val store = VpnProfileStore(context)
            val number = store.list().count { it.kind == VpnKind.XRAY && it.free == free } + 1
            val name = context.getString(if (free) R.string.vpn_profile_free_n else R.string.vpn_profile_personal_n, number)
            return store.add(VpnKind.XRAY, name, free, text)
        }
    }
}
