package com.enigma.farsilandtv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Startup intro (1.33): on charcoal, the gold play mark moves gently forward while a warm light sweeps across it.
 * When it has formed, [onImpact] fires (the anvil sound) with a restrained gold pulse; then the view waits for the
 * home page and shows a small spinner while it is still loading. With reduced motion everything is static.
 *
 * It draws on its own thread into a surface above the page, so the home page loading on the main thread at the
 * same time cannot make the animation stutter. [onImpact] runs on that thread to keep the sound in sync.
 */
internal class IntroView(
    context: Context,
    private val mark: Bitmap,
    private val reducedMotion: Boolean,
    private val onImpact: () -> Unit,
    private val onFormed: () -> Unit
) : SurfaceView(context), SurfaceHolder.Callback {
    private val main = Handler(Looper.getMainLooper())
    private val start = SystemClock.uptimeMillis()
    @Volatile private var impactDone = false
    @Volatile private var formedDone = false
    @Volatile var holding = false
        private set
    @Volatile private var stopped = false
    @Volatile private var exitStart = 0L
    @Volatile private var exitDone: (() -> Unit)? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private var w = 0
    private var h = 0

    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val brightPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
        color = Color.rgb(246, 214, 138)
    }
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(242, 181, 68)
    }
    private val target = RectF()
    private val arc = RectF()
    private val band = Path()
    private var face: Bitmap? = null
    private var bright: Bitmap? = null
    private var glow: Bitmap? = null
    private var faceSize = 1f
    private var frames = 0

    init {
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        setZOrderOnTop(true)
        isFocusable = true
        contentDescription = "Amin Player"
    }

    /** The home page is ready or failed: nothing more to wait for. */
    fun stop() {
        stopped = true
    }

    /** Fades the intro out over the page underneath, then calls [done] on the main thread. */
    fun fadeOut(done: () -> Unit) {
        stopped = true
        exitDone = done
        exitStart = SystemClock.uptimeMillis()
        if (!running) main.post(done)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        stopThread()
        w = width
        h = height
        running = true
        thread = Thread({ loop() }, "amin-intro").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = stopThread()

    private fun stopThread() {
        running = false
        thread?.let { if (it !== Thread.currentThread()) runCatching { it.join(500) } }
        thread = null
    }

    // Everything costly is prepared once, so each frame only places bitmaps (no layers, no new shaders).
    private fun prepare() {
        lightPaint.shader = RadialGradient(w / 2f, h * 0.44f, max(1f, h * 0.8f),
            intArrayOf(Color.argb(150, 70, 52, 30), Color.argb(0, 20, 17, 20)), null, Shader.TileMode.CLAMP)
        faceSize = min(w, h) * 0.36f
        val side = max(1, faceSize.toInt())
        val scaled = Bitmap.createScaledBitmap(mark, side, side, true)
        face = scaled
        bright = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { tinted ->
            Canvas(tinted).apply {
                drawBitmap(scaled, 0f, 0f, null)
                drawColor(Color.rgb(255, 240, 200), PorterDuff.Mode.SRC_ATOP)
            }
        }
        val glowSide = max(2, (faceSize * 1.6f).toInt())
        glow = Bitmap.createBitmap(glowSide, glowSide, Bitmap.Config.ARGB_8888).also { halo ->
            val r = glowSide / 2f
            Canvas(halo).drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(r, r, r, intArrayOf(Color.argb(150, 242, 181, 68), Color.argb(150, 242, 181, 68),
                    Color.argb(0, 242, 181, 68)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            })
        }
        wordPaint.textSize = h * 0.058f
    }

    private fun loop() {
        if (face == null || faceSize != min(w, h) * 0.36f) prepare()
        while (running) {
            val canvas = (if (Build.VERSION.SDK_INT >= 26) runCatching { holder.lockHardwareCanvas() }.getOrNull() else null)
                ?: runCatching { holder.lockCanvas() }.getOrNull() ?: break
            val finished = try { drawFrame(canvas) } finally { runCatching { holder.unlockCanvasAndPost(canvas) } }
            if (finished) {
                running = false
                exitDone?.let { main.post(it) }
                break
            }
            // Static frames (reduced motion, or only the spinner left) need no full frame rate.
            if (reducedMotion && formedDone) SystemClock.sleep(100)
        }
    }

    private fun ease(x: Float) = 1f - (1f - x.coerceIn(0f, 1f)).pow(3)
    private fun window(now: Long, from: Long, to: Long) = ((now - from).toFloat() / (to - from)).coerceIn(0f, 1f)

    /** Draws one frame; returns true when the fade-out has completed. */
    private fun drawFrame(canvas: Canvas): Boolean {
        val clock = SystemClock.uptimeMillis()
        val now = clock - start
        val fade = if (exitStart == 0L) 1f
            else if (reducedMotion) 0f else 1f - ((clock - exitStart).toFloat() / EXIT).coerceIn(0f, 1f)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (fade <= 0f) return true
        val face = face ?: return false
        val form = if (reducedMotion) 1f else ease(window(now, FORM_FROM, FORM_TO))
        val impactAt = if (reducedMotion) REDUCED_IMPACT else IMPACT
        if (!impactDone && now >= impactAt) { impactDone = true; onImpact() }
        val end = if (reducedMotion) REDUCED_END else END
        frames++
        if (!formedDone && now >= end) {
            formedDone = true
            holding = true
            Log.i("AminIntro", "drew $frames frames in $now ms")
            main.post(onFormed)
        }

        val wf = w.toFloat()
        val hf = h.toFloat()
        canvas.drawColor(Color.argb((255 * fade).toInt(), 20, 17, 20))
        lightPaint.alpha = (255 * form * fade).toInt()
        canvas.drawRect(0f, 0f, wf, hf, lightPaint)

        val size = faceSize * (0.86f + 0.14f * form)
        val cx = wf / 2f
        val cy = hf * 0.44f
        target.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)

        // Gold pulse behind the mark at the impact.
        val since = now - impactAt
        if (!reducedMotion && since in 0..PULSE) {
            val p = since.toFloat() / PULSE
            val half = size * (0.78f + 0.3f * ease(p))
            glowPaint.alpha = (255 * (1 - p).pow(1.5f) * fade).toInt()
            arc.set(cx - half, cy - half, cx + half, cy + half)
            glow?.let { canvas.drawBitmap(it, null, arc, glowPaint) }
        }

        // The mark, a warm light band sweeping across it while it forms, and a brief flash at the impact.
        markPaint.alpha = (255 * form * fade).toInt()
        canvas.drawBitmap(face, null, target, markPaint)
        val sweep = if (reducedMotion) -1f else window(now, SWEEP_FROM, SWEEP_TO)
        val sheet = bright
        if (sheet != null && sweep in 0.001f..0.999f) {
            val x = target.left - size * 0.5f + size * 2f * sweep
            // Three nested diagonal bands give the light a soft edge without an offscreen layer.
            for ((width, alpha) in BANDS) {
                val half = size * width / 2
                band.reset()
                band.moveTo(x - half + size * 0.25f, target.top)
                band.lineTo(x + half + size * 0.25f, target.top)
                band.lineTo(x + half - size * 0.25f, target.bottom)
                band.lineTo(x - half - size * 0.25f, target.bottom)
                band.close()
                val saved = canvas.save()
                canvas.clipPath(band)
                brightPaint.alpha = (alpha * form * fade).toInt()
                canvas.drawBitmap(sheet, null, target, brightPaint)
                canvas.restoreToCount(saved)
            }
        }
        if (sheet != null && !reducedMotion && since in 0..FLASH) {
            brightPaint.alpha = (110 * (1f - since.toFloat() / FLASH) * fade).toInt()
            canvas.drawBitmap(sheet, null, target, brightPaint)
        }

        // Wordmark under the mark.
        val word = if (reducedMotion) 1f else ease(window(now, WORD_FROM, WORD_TO))
        wordPaint.alpha = (255 * word * fade).toInt()
        val wordY = cy + size * 0.62f + wordPaint.textSize * 0.9f
        canvas.drawText("Amin Player", cx, wordY, wordPaint)

        // Still loading after the intro: a small spinner.
        if (holding && !stopped) {
            val r = hf * 0.022f
            val y = wordY + hf * 0.075f
            arc.set(cx - r, y - r, cx + r, y + r)
            spinnerPaint.strokeWidth = r * 0.32f
            spinnerPaint.alpha = (220 * fade).toInt()
            val turn = if (reducedMotion) 0f else (now % 1000L) * 0.36f
            canvas.drawArc(arc, turn, 270f, false, spinnerPaint)
        }
        return false
    }

    companion object {
        val CHARCOAL = Color.rgb(20, 17, 20)
        private val BANDS = listOf(0.34f to 45, 0.22f to 70, 0.11f to 95)
        // Timeline in milliseconds (about 2.5 s), then a 380 ms fade into the home page.
        const val FORM_FROM = 150L
        const val FORM_TO = 1450L
        const val SWEEP_FROM = 420L
        const val SWEEP_TO = 1500L
        const val IMPACT = 1500L
        const val PULSE = 700L
        const val FLASH = 180L
        const val WORD_FROM = 1600L
        const val WORD_TO = 2100L
        const val END = 2500L
        const val EXIT = 380L
        const val REDUCED_IMPACT = 300L
        const val REDUCED_END = 1200L
    }
}
