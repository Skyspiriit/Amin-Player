package com.enigma.farsilandtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
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

class VpnSetupActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var secrets: VpnSecrets
    private lateinit var status: TextView
    private lateinit var field: EditText
    private lateinit var connect: Button
    private lateinit var save: Button
    private lateinit var importDrive: Button
    private lateinit var importLocal: Button
    private lateinit var forget: Button
    private var busy = false
    private var connectAfterConsent = false
    private val observer: () -> Unit = { update() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectAfterConsent = savedInstanceState?.getBoolean("connectAfterConsent") ?: false
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        secrets = VpnSecrets(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(48), dp(22), dp(48), dp(30))
            setBackgroundColor(Color.rgb(13, 22, 25))
        }
        fun text(label: String, size: Float) = TextView(this).apply {
            text = label
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(8))
        }.also { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        text("FreeSocks VPN", 26f)
        status = text("", 18f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        connect = button(content, R.string.vpn_connect) { startOrStop() }
        importLocal = button(content, R.string.vpn_local_import) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, LocalVpnImportActivity::class.java), LOCAL_IMPORT)
        }
        importDrive = button(content, R.string.vpn_import_drive) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, LocalVpnImportActivity::class.java)
                .putExtra(LocalVpnImportActivity.DRIVE_IMPORT, true), LOCAL_IMPORT)
        }.apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_google_drive, 0, 0, 0)
            compoundDrawablePadding = dp(12)
        }
        text(getString(R.string.vpn_setup_guide), 17f)
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
            if (Build.VERSION.SDK_INT >= 26) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { saveAddress(text.toString()); true } else false
            }
        }
        content.addView(field, LinearLayout.LayoutParams(-1, dp(52)))
        save = button(content, R.string.vpn_save) { saveAddress(field.text.toString()) }
        forget = button(content, R.string.vpn_forget) {
            secrets.clear()
            VpnStatus.stop()
            update()
        }
        text(getString(R.string.vpn_privacy_hint), 15f)
        button(content, R.string.vpn_back) { finish() }
        setContentView(ScrollView(this).apply { addView(content); isFillViewport = true })
        update()
        if (secrets.configured()) connect.requestFocus() else importLocal.requestFocus()
        if (savedInstanceState == null && intent.getBooleanExtra(CONNECT_REQUEST, false) &&
            VpnStatus.current.phase in setOf(VpnPhase.OFF, VpnPhase.ERROR)) {
            explainConsent()
        }
    }

    private fun button(parent: LinearLayout, label: Int, action: () -> Unit): Button = Button(this).apply {
        setText(label)
        textSize = 18f
        gravity = Gravity.CENTER
        isAllCaps = false
        setOnClickListener { action() }
    }.also { parent.addView(it, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(6) }) }

    private fun update() {
        if (isDestroyed) return
        val state = VpnStatus.current
        connect.text = state.actionLabel(this)
        connect.isEnabled = !busy && state.phase != VpnPhase.STOPPING
        status.text = when {
            busy -> getString(R.string.vpn_saving)
            state.phase == VpnPhase.OFF && secrets.configured() -> getString(R.string.vpn_saved)
            state.phase == VpnPhase.OFF -> getString(R.string.vpn_setup_missing)
            else -> state.statusLabel(this)
        }
        val editable = !busy && state.phase in setOf(VpnPhase.OFF, VpnPhase.ERROR)
        field.isEnabled = editable
        save.isEnabled = editable
        importDrive.isEnabled = editable
        importLocal.isEnabled = editable
        forget.isEnabled = editable && secrets.configured()
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
                    if (saved) { VpnStatus.stop(); update(); connect.requestFocus() }
                    else { update(); status.setText(R.string.vpn_save_failed) }
                }
            }
        }
    }

    private fun startOrStop() {
        when (VpnStatus.current.phase) {
            VpnPhase.ON, VpnPhase.CONNECTING -> AminVpnService.disconnect(this)
            VpnPhase.STOPPING -> Unit
            VpnPhase.OFF, VpnPhase.ERROR -> {
                explainConsent()
            }
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
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val CONNECT_REQUEST = "connect-vpn"
        private const val VPN_CONSENT = 801
        private const val LOCAL_IMPORT = 804
    }
}
