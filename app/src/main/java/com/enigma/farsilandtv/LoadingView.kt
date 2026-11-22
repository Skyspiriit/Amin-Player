package com.enigma.farsilandtv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Loading screen between pages (1.34): the gold play mark from the intro with a gentle breathing glow, a gliding
 * gold progress line and a Persian message, in the colours of the chosen theme. Like the intro it draws on its
 * own thread above the page, so the page loading on the main thread cannot make it stutter.
 */
internal class LoadingView(context: Context, private val mark: Bitmap) : SurfaceView(context), SurfaceHolder.Callback {
    @Volatile var message: String = ""
    @Volatile private var palette = AppThemes.palette(AppThemes.ids.first())
    @Volatile private var shownAt = 0L
    @Volatile private var hideAt = 0L
    @Volatile private var running = false
    private var thread: Thread? = null
    private var w = 0
    private var h = 0
    private var face: Bitmap? = null
    private var glow: Bitmap? = null
    private var faceSize = 1f
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val target = RectF()

    init {
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        setZOrderOnTop(true)
        visibility = View.GONE
    }

    /** Shows the screen (fading in) in the colours of [theme]. */
    fun show(theme: String, text: String) {
        palette = AppThemes.palette(theme)
        message = text
        hideAt = 0L
        if (visibility != View.VISIBLE) {
            shownAt = SystemClock.uptimeMillis()
            visibility = View.VISIBLE
        }
    }

    /** Fades out, then removes itself from the screen. */
    fun hide(immediate: Boolean = false) {
        if (visibility != View.VISIBLE) return
        if (immediate || !running) { visibility = View.GONE; return }
        if (hideAt == 0L) hideAt = SystemClock.uptimeMillis()
        postDelayed({ if (hideAt != 0L) visibility = View.GONE }, FADE + 40)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        stopThread()
        w = width
        h = height
        running = true
        thread = Thread({ loop() }, "amin-loading").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = stopThread()

    private fun stopThread() {
        running = false
        thread?.let { if (it !== Thread.currentThread()) runCatching { it.join(500) } }
        thread = null
    }

    private fun prepare() {
        faceSize = min(w, h) * 0.2f
        val side = max(1, faceSize.toInt())
        face = Bitmap.createScaledBitmap(mark, side, side, true)
        val glowSide = max(2, (faceSize * 1.9f).toInt())
        glow = Bitmap.createBitmap(glowSide, glowSide, Bitmap.Config.ARGB_8888).also { halo ->
            val r = glowSide / 2f
            Canvas(halo).drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(r, r, r, intArrayOf(Color.argb(140, 255, 255, 255), Color.argb(140, 255, 255, 255),
                    Color.argb(0, 255, 255, 255)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            })
        }
        textPaint.textSize = h * 0.042f
    }

    private fun loop() {
        if (face == null || faceSize != min(w, h) * 0.2f) prepare()
        while (running) {
            val canvas = (if (Build.VERSION.SDK_INT >= 26) runCatching { holder.lockHardwareCanvas() }.getOrNull() else null)
                ?: runCatching { holder.lockCanvas() }.getOrNull() ?: break
            try { drawFrame(canvas) } finally { runCatching { holder.unlockCanvasAndPost(canvas) } }
        }
    }

    private fun drawFrame(canvas: Canvas) {
        val clock = SystemClock.uptimeMillis()
        val tone = palette
        val appear = ((clock - shownAt).toFloat() / FADE).coerceIn(0f, 1f)
        val leave = if (hideAt == 0L) 1f else 1f - ((clock - hideAt).toFloat() / FADE).coerceIn(0f, 1f)
        val alpha = appear * leave
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (alpha <= 0f) return
        val wf = w.toFloat()
        val hf = h.toFloat()
        canvas.drawColor(Color.argb((255 * alpha).toInt(), Color.red(tone.bg), Color.green(tone.bg), Color.blue(tone.bg)))
        lightPaint.shader = lightPaint.shader ?: RadialGradient(wf / 2, hf * 0.42f, max(1f, hf * 0.75f),
            intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255)), null, Shader.TileMode.CLAMP)
        lightPaint.alpha = (60 * alpha).toInt()
        canvas.drawRect(0f, 0f, wf, hf, lightPaint)

        // Breathing glow and mark.
        val phase = ((clock % BREATH).toFloat() / BREATH) * 2 * PI.toFloat()
        val breath = 0.5f + 0.5f * sin(phase)
        val cx = wf / 2
        val cy = hf * 0.42f
        val glowHalf = faceSize * (0.85f + 0.1f * breath)
        target.set(cx - glowHalf, cy - glowHalf, cx + glowHalf, cy + glowHalf)
        glowPaint.colorFilter = android.graphics.PorterDuffColorFilter(tone.accent, PorterDuff.Mode.SRC_IN)
        glowPaint.alpha = ((0.35f + 0.35f * breath) * 255 * alpha).toInt()
        glow?.let { canvas.drawBitmap(it, null, target, glowPaint) }
        val size = faceSize * (0.97f + 0.03f * breath)
        target.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        bitmapPaint.alpha = (255 * alpha).toInt()
        face?.let { canvas.drawBitmap(it, null, target, bitmapPaint) }

        // Gliding progress line (right to left, as Persian reads).
        val lineY = cy + faceSize * 0.85f
        val half = wf * 0.13f
        linePaint.strokeWidth = hf * 0.006f
        linePaint.color = tone.text
        linePaint.alpha = (40 * alpha).toInt()
        canvas.drawLine(cx - half, lineY, cx + half, lineY, linePaint)
        val p = (clock % GLIDE).toFloat() / GLIDE
        val head = cx + half - (half * 2.6f) * p
        val tail = head + half * 0.6f
        val from = max(cx - half, min(head, tail))
        val to = min(cx + half, max(head, tail))
        if (to > from) {
            linePaint.color = tone.accent
            linePaint.alpha = (255 * alpha).toInt()
            canvas.drawLine(from, lineY, to, lineY, linePaint)
        }

        textPaint.color = tone.text
        textPaint.alpha = (230 * alpha).toInt()
        canvas.drawText(message, cx, lineY + textPaint.textSize * 1.9f, textPaint)
    }

    companion object {
        const val FADE = 220L
        const val BREATH = 1600L
        const val GLIDE = 1300L
    }
}
