package com.enigma.farsilandtv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Profile bytes never leave private memory before explicit on-TV approval. */
class LocalVpnImportActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean()
    private var session: LocalVpnImportServer? = null
    private var confirmation: AlertDialog? = null
    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var pairing: TextView
    private lateinit var certificate: TextView
    private lateinit var qr: ImageView
    private var qrBitmap: Bitmap? = null
    private lateinit var cancel: Button
    private val tick = object : Runnable {
        override fun run() {
            val server = session ?: return
            if (!active.get() || server.closed) return
            val seconds = ((server.deadline - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
            status.text = getString(R.string.vpn_local_time, seconds / 60, seconds % 60)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        active.set(true)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(40), dp(16), dp(40), dp(20))
            setBackgroundColor(Color.rgb(13, 22, 25))
        }
        fun label(value: String, size: Float, parent: LinearLayout = content) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, dp(6))
            isSaveEnabled = false
        }.also { parent.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        label(getString(R.string.vpn_local_import), 25f)
        label(getString(R.string.vpn_local_guide), 16f)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        qr = ImageView(this).apply {
            contentDescription = getString(R.string.vpn_local_qr)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = false
            isClickable = false
            isSaveEnabled = false
            visibility = View.INVISIBLE
        }
        row.addView(qr, LinearLayout.LayoutParams(dp(248), dp(248)).apply { marginStart = dp(24) })
        content.addView(row, LinearLayout.LayoutParams(-1, -2))
        status = label(getString(R.string.vpn_local_starting), 17f, details)
        address = label("", 22f, details).apply { textDirection = View.TEXT_DIRECTION_LTR; typeface = Typeface.MONOSPACE }
        pairing = label("", 26f, details).apply { textDirection = View.TEXT_DIRECTION_LTR; typeface = Typeface.MONOSPACE }
        label(getString(R.string.vpn_local_certificate), 14f, details)
        certificate = label("", 12f, details).apply { textDirection = View.TEXT_DIRECTION_LTR; typeface = Typeface.MONOSPACE }
        cancel = Button(this).apply {
            setText(R.string.vpn_local_close)
            textSize = 18f
            isAllCaps = false
            setOnClickListener { finish() }
        }
        content.addView(cancel, LinearLayout.LayoutParams(-1, dp(52)))
        setContentView(ScrollView(this).apply { addView(content); isFillViewport = true })
        cancel.requestFocus()
        if (VpnStatus.current.phase !in setOf(VpnPhase.OFF, VpnPhase.ERROR)) { finish(); return }
        worker.execute {
            // Retire unused temporary identities from earlier private test builds only.
            runCatching {
                val oldKeys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                oldKeys.aliases().toList().filter { it.startsWith("amin-player-import-") }
                    .forEach(oldKeys::deleteEntry)
            }
            val result = runCatching { LocalVpnImportServer.start(this, ::received) {
                handler.post { expired() }
            } }
            val image = result.getOrNull()?.let { server -> runCatching {
                val matrix = LocalImportQr.matrix(server.url, dp(248).coerceIn(160, 1024))
                val pixels = IntArray(matrix.width * matrix.height) { index ->
                    if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
                }
                Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            }.getOrNull() }
            handler.post {
                if (!active.get()) { result.getOrNull()?.close(); image?.recycle(); return@post }
                result.fold(onSuccess = { server ->
                    session = server
                    address.text = server.url
                    pairing.text = getString(R.string.vpn_local_code, server.code)
                    certificate.text = server.fingerprint.chunked(48).joinToString("\n")
                    qrBitmap = image
                    qr.setImageBitmap(image)
                    qr.visibility = if (image != null) View.VISIBLE else View.INVISIBLE
                    tick.run()
                }, onFailure = { status.setText(R.string.vpn_local_unavailable) })
            }
        }
    }

    private fun received(bytes: ByteArray) {
        if (!active.get()) { bytes.fill(0); return }
        try { worker.execute {
            val result = try { runCatching {
                val value = VpnImport.text(LocalImportHttp.text(bytes))
                if (!VpnImport.remote(value)) VpnEngine.nodes(value)
                value
            } } finally { bytes.fill(0) }
            handler.post {
                if (!active.get() || session?.closed != false) return@post
                result.fold(onSuccess = { value -> confirm(value) }, onFailure = {
                    handler.removeCallbacks(tick)
                    status.setText(R.string.vpn_local_invalid)
                    session?.retry()
                })
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { bytes.fill(0) }
    }

    private fun confirm(value: String) {
        handler.removeCallbacks(tick)
        status.setText(R.string.vpn_local_received)
        confirmation = AlertDialog.Builder(this).setTitle(R.string.vpn_local_received)
            .setMessage(if (VpnSecrets(this).configured()) R.string.vpn_local_replace else R.string.vpn_local_confirm)
            .setNegativeButton(android.R.string.cancel) { _, _ -> session?.retry(); tick.run() }
            .setPositiveButton(R.string.vpn_local_save) { _, _ -> save(value) }
            .setOnCancelListener { session?.retry(); tick.run() }
            .create().apply {
                setOnShowListener { getButton(AlertDialog.BUTTON_NEGATIVE).apply {
                    isFocusableInTouchMode = true
                    requestFocus()
                } }
                setOnDismissListener { confirmation = null }
                show()
                window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
    }

    private fun save(value: String) {
        if (!active.get() || session?.closed != false) return
        session?.close()
        session = null
        cancel.isEnabled = false
        status.setText(R.string.vpn_saving)
        worker.execute {
            val saved = runCatching {
                check(active.get() && VpnStatus.current.phase in setOf(VpnPhase.OFF, VpnPhase.ERROR))
                VpnSecrets(this).save(value)
            }.isSuccess
            handler.post {
                if (!active.get()) return@post
                if (saved) { VpnStatus.stop(); setResult(RESULT_OK); finish() }
                else { status.setText(R.string.vpn_save_failed); cancel.isEnabled = true }
            }
        }
    }

    private fun expired() {
        if (!active.get() || session == null) return
        handler.removeCallbacks(tick)
        confirmation?.dismiss()
        confirmation = null
        address.text = ""
        pairing.text = ""
        certificate.text = ""
        clearQr()
        status.setText(R.string.vpn_local_expired)
        cancel.requestFocus()
    }

    override fun onStop() {
        active.set(false)
        handler.removeCallbacksAndMessages(null)
        confirmation?.dismiss()
        confirmation = null
        session?.close()
        session = null
        clearQr()
        super.onStop()
        if (!isFinishing) finish()
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
    private fun clearQr() {
        if (::qr.isInitialized) { qr.setImageDrawable(null); qr.visibility = View.INVISIBLE }
        qrBitmap?.recycle()
        qrBitmap = null
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}
