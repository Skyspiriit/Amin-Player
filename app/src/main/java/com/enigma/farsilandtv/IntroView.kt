package com.enigma.farsilandtv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ComposeShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Startup intro (1.37), about 2.1 s, drawn natively in 2D. On charcoal the Amin Player mark appears as a dark mould
 * that never moves; molten gold rises inside it from bottom to top; at the moment it is full one heavy hammer strike
 * sounds and the mark turns into the clean finished gold logo; AMIN PLAYER shows briefly and the intro ends.
 *
 * Sync: the sound is one [AudioTrack] holding [LEAD] ms of silence followed by the strike, started as the fill begins.
 * While the silence plays, the track's own presentation timestamp tells exactly when its first sample left the audio
 * output, so the strike's moment (first sample + [LEAD]) is known before it happens and the fill is timed to finish on it.
 * No guessing of the device's audio latency. Without sound (switched off, or no timestamp) a fixed timeline is used.
 * Everything is drawn on its own thread into a surface above the page, so the page loading cannot make it stutter.
 */
internal class IntroView(
    context: Context,
    private val mark: Bitmap,
    private val strike: ShortArray?,
    private val volume: Float,
    private val onFormed: () -> Unit
) : SurfaceView(context), SurfaceHolder.Callback {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var start = 0L          // nanoTime of the first frame
    @Volatile private var impact = 0L         // nanoTime the mould is full (and the strike is heard)
    @Volatile private var impactMeasured = false
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

    private var track: AudioTrack? = null
    @Volatile private var soundStarted = false
    private var soundAt = 0L
    private var fillShown = 0f
    private val stamp = AudioTimestamp()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.24f
        color = Color.rgb(246, 214, 138)
    }
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(242, 181, 68)
    }
    private val target = RectF()
    private val arc = RectF()
    private val surface = Path()
    private var mould: Bitmap? = null
    private var molten: Bitmap? = null
    private var hot: Bitmap? = null
    private var hotShader: BitmapShader? = null
    private val hotPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var crest: Bitmap? = null
    private var gold: Bitmap? = null
    private var bright: Bitmap? = null
    private var glow: Bitmap? = null
    private var size = 1f
    private var frames = 0

    init {
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        setZOrderOnTop(true)
        isFocusable = true
        contentDescription = "Amin Player"
        strike?.let { pcm ->
            // A static track: the whole sound is in memory, so play() starts it with no loading in between.
            track = runCatching {
                val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                val format = AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                val lead = (RATE * LEAD / 1000).toInt()
                val all = ShortArray(lead + pcm.size).also { System.arraycopy(pcm, 0, it, lead, pcm.size) }
                AudioTrack(attributes, format, all.size * 2, AudioTrack.MODE_STATIC, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE).also {
                    it.write(all, 0, all.size)
                    it.setVolume(volume)
                }
            }.getOrNull()
        }
    }

    /** The home page is ready or failed: nothing more to wait for. */
    fun stop() {
        stopped = true
    }

    /** Fades the intro out over the page underneath, then calls [done] on the main thread. */
    fun fadeOut(done: () -> Unit) {
        stopped = true
        // Skipped before the strike: the strike must not sound over the home page.
        if (System.nanoTime() < impact) release()
        exitDone = done
        exitStart = System.nanoTime()
        if (!running) main.post(done)
    }

    /** Silences and frees the strike (leaving the app, or the intro skipped before it). */
    fun release() {
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        runCatching { t.release() }
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

    /** The mark's own shape filled with [shader] (the mark's alpha is the mask). */
    private fun tinted(scaled: Bitmap, shader: Shader): Bitmap =
        Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888).also { out ->
            Canvas(out).apply {
                drawBitmap(scaled, 0f, 0f, null)
                drawPaint(Paint().apply { this.shader = shader; xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
            }
        }

    private fun prepare() {
        size = min(w, h) * 0.36f
        val side = max(1, size.toInt())
        val s = side.toFloat()
        val scaled = Bitmap.createScaledBitmap(mark, side, side, true)
        gold = scaled
        // The mould and the metal follow only the solid parts of the mark (ring and play symbol), not its soft glow.
        val solid = scaled.copy(Bitmap.Config.ARGB_8888, true).also { b ->
            val px = IntArray(side * side)
            b.getPixels(px, 0, side, 0, 0, side, side)
            for (i in px.indices) {
                val a = ((Color.alpha(px[i]) - 150) * 255 / 70).coerceIn(0, 255)
                px[i] = Color.argb(a, 255, 255, 255)
            }
            b.setPixels(px, 0, side, 0, 0, side, side)
        }
        // The mould: dark iron with a faint warm edge on top (a lighter copy one pixel higher, under the dark face).
        mould = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { out ->
            val edge = tinted(solid, LinearGradient(0f, 0f, 0f, s, Color.rgb(98, 78, 60), Color.rgb(48, 38, 32), Shader.TileMode.CLAMP))
            val face = tinted(solid, LinearGradient(0f, 0f, 0f, s, Color.rgb(40, 33, 30), Color.rgb(26, 21, 20), Shader.TileMode.CLAMP))
            Canvas(out).apply {
                drawBitmap(edge, 0f, -max(1f, s * 0.006f), null)
                drawBitmap(face, 0f, 0f, null)
            }
            edge.recycle(); face.recycle()
        }
        // Molten metal: deep orange low down, hotter toward the top.
        molten = tinted(solid, LinearGradient(0f, s, 0f, 0f,
            intArrayOf(Color.rgb(150, 38, 10), Color.rgb(214, 86, 20), Color.rgb(240, 128, 34)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP))
        hot = tinted(solid, LinearGradient(0f, 0f, 0f, s, Color.rgb(255, 196, 92), Color.rgb(255, 164, 60), Shader.TileMode.CLAMP))
        // The bright crest of the rising surface and the white-gold flash at the finish.
        crest = tinted(solid, LinearGradient(0f, 0f, 0f, s, Color.rgb(255, 246, 214), Color.rgb(255, 222, 150), Shader.TileMode.CLAMP))
        bright = tinted(solid, LinearGradient(0f, 0f, 0f, s, Color.rgb(255, 248, 226), Color.rgb(255, 226, 160), Shader.TileMode.CLAMP))
        val glowSide = max(2, (size * 1.7f).toInt())
        glow = Bitmap.createBitmap(glowSide, glowSide, Bitmap.Config.ARGB_8888).also { halo ->
            val r = glowSide / 2f
            Canvas(halo).drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(r, r, r, intArrayOf(Color.argb(150, 255, 170, 70), Color.argb(70, 242, 140, 40),
                    Color.argb(0, 242, 130, 30)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            })
        }
        backPaint.shader = RadialGradient(w / 2f, h * 0.44f, max(1f, h * 0.75f),
            intArrayOf(Color.argb(255, 34, 28, 30), Color.argb(255, 20, 17, 20)), null, Shader.TileMode.CLAMP)
        wordPaint.textSize = h * 0.05f
        // The hot metal as a shader placed over the mark's position on screen (which never moves).
        hot?.let { bmp ->
            hotShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply {
                    setScale(size / bmp.width, size / bmp.height)
                    postTranslate(w / 2f - size / 2, h * 0.44f - size / 2)
                })
            }
        }
    }

    // One kind of canvas for the whole life of the surface: Android cannot mix software and hardware locks on it.
    private var hardware: Boolean? = null
    private fun lock(): Canvas? {
        if (hardware != false && Build.VERSION.SDK_INT >= 26) {
            runCatching { holder.lockHardwareCanvas() }.getOrNull()?.let { hardware = true; return it }
            if (hardware == true) return null
        }
        hardware = false
        return runCatching { holder.lockCanvas() }.getOrNull()
    }

    private fun loop() {
        if (gold == null || size != min(w, h) * 0.36f) {
            // Cover the page with charcoal at once, before the (short) preparation, so nothing else flashes.
            lock()?.let { c ->
                try { c.drawColor(CHARCOAL) } finally { runCatching { holder.unlockCanvasAndPost(c) } }
            }
            prepare()
        }
        if (start == 0L) {
            start = System.nanoTime()
            if (track != null) {
                soundAt = start + ms(FILL_FROM)
                impact = soundAt + ms(EXPECTED_LATENCY + LEAD)
            } else impact = start + ms(FILL_FROM + FILL)
        }
        while (running) {
            val canvas = lock() ?: break
            val finished = try { drawFrame(canvas) } finally { runCatching { holder.unlockCanvasAndPost(canvas) } }
            if (finished) {
                running = false
                exitDone?.let { main.post(it) }
                break
            }
            if (formedDone && stopped) SystemClock.sleep(8)
        }
    }

    /** Starts the track as the fill begins, then reads when its first (silent) sample played and sets the finish. */
    private fun timeSound(now: Long) {
        val t = track ?: return
        if (!soundStarted && now >= soundAt && exitStart == 0L) {
            soundStarted = true
            runCatching { t.play() }
        }
        if (soundStarted && !impactMeasured) {
            val ok = runCatching { t.getTimestamp(stamp) }.getOrDefault(false)
            if (ok && stamp.framePosition > 0) {
                val firstSample = stamp.nanoTime - stamp.framePosition * 1_000_000_000L / RATE
                // The strike is LEAD after the first sample. A frame posted now reaches the screen about a refresh
                // later, so the picture changes that much earlier.
                impact = firstSample + ms(LEAD) - ms(DISPLAY_LEAD)
                impactMeasured = true
                Log.i("AminIntro", "audio output delay ${(firstSample - soundAt) / 1_000_000} ms, strike due in ${(impact - now) / 1_000_000} ms")
            } else if (now - soundAt > ms(MEASURE_LIMIT)) impactMeasured = true   // no timestamp: keep the estimate
        }
    }

    private fun ms(v: Long) = v * 1_000_000L
    private fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    private fun window(now: Long, from: Long, to: Long) =
        if (to <= from) (if (now >= to) 1f else 0f) else ((now - from).toFloat() / (to - from)).coerceIn(0f, 1f)

    /** Draws one frame; returns true when the fade-out has completed. */
    private fun drawFrame(canvas: Canvas): Boolean {
        val now = System.nanoTime()
        timeSound(now)
        val fade = if (exitStart == 0L) 1f else 1f - ((now - exitStart).toFloat() / ms(EXIT)).coerceIn(0f, 1f)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (fade <= 0f) return true
        val gold = gold ?: return false
        frames++
        val end = impact + ms(HOLD)
        if (!formedDone && now >= end) {
            formedDone = true
            holding = true
            Log.i("AminIntro", "drew $frames frames in ${(now - start) / 1_000_000} ms")
            main.post(onFormed)
        }

        val wf = w.toFloat()
        val hf = h.toFloat()
        backPaint.alpha = (255 * fade).toInt()
        canvas.drawRect(0f, 0f, wf, hf, backPaint)
        val cx = wf / 2f
        val cy = hf * 0.44f
        target.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        val done = now >= impact
        val since = (now - impact) / 1_000_000L

        // The mould, fading in, then underneath the metal until the finish.
        val appear = smooth(window(now, start, start + ms(MOULD_IN)))
        if (!done || since < FLASH) {
            paint.alpha = (255 * appear * fade).toInt()
            mould?.let { canvas.drawBitmap(it, null, target, paint) }
        }

        if (!done) {
            // Molten gold rising from the bottom: a gently easing level with a slow, slight swell on its surface.
            // Never lower: the finish may move a little once the audio delay is measured.
            fillShown = max(fillShown, smooth(window(now, start + ms(FILL_FROM), impact)).let { 0.04f + 0.96f * it })
            val fill = fillShown
            val level = target.bottom - target.height() * fill
            val t = (now - start) / 1_000_000_000f
            val amp = target.height() * 0.012f * (1f - fill * 0.6f)
            surface.reset()
            surface.moveTo(target.left, target.bottom)
            var x = target.left
            val step = target.width() / 24f
            surface.lineTo(x, level + amp * sin(t * 5.2f))
            while (x < target.right) {
                x += step
                surface.lineTo(x, level + amp * sin(t * 5.2f + (x - target.left) / target.width() * 6.3f))
            }
            surface.lineTo(target.right, target.bottom)
            surface.close()
            val saved = canvas.save()
            canvas.clipPath(surface)
            paint.alpha = (255 * fade).toInt()
            molten?.let { canvas.drawBitmap(it, null, target, paint) }
            canvas.restoreToCount(saved)
            // Hotter metal at the top of the pour, fading smoothly into the deeper colour below.
            hotShader?.let { metal ->
                val falloff = LinearGradient(0f, level - amp, 0f, level + target.height() * 0.34f,
                    Color.argb((215 * fade).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
                hotPaint.shader = ComposeShader(metal, falloff, PorterDuff.Mode.DST_IN)
                val h2 = canvas.save()
                canvas.clipPath(surface)
                canvas.drawRect(target, hotPaint)
                canvas.restoreToCount(h2)
            }
            // The bright crest just under the surface.
            val crestTop = level - amp
            val band = target.height() * 0.035f
            val c = canvas.save()
            canvas.clipPath(surface)
            canvas.clipRect(target.left, crestTop, target.right, crestTop + band * 1.1f)
            paint.alpha = (165 * fade).toInt()
            crest?.let { canvas.drawBitmap(it, null, target, paint) }
            canvas.restoreToCount(c)
            // Heat glow behind the mark, growing as it fills.
            val half = size * (0.7f + 0.15f * fill)
            arc.set(cx - half, cy - half, cx + half, cy + half)
            glowPaint.alpha = (110 * fill * fade).toInt()
            glow?.let { canvas.drawBitmap(it, null, arc, glowPaint) }
        } else {
            // The finish: the clean gold logo, with a short white-gold flash and a soft glow that settles.
            if (since < GLOW) {
                val p = since.toFloat() / GLOW
                val half = size * (0.8f + 0.12f * p)
                arc.set(cx - half, cy - half, cx + half, cy + half)
                glowPaint.alpha = (170 * (1 - p).pow(1.4f) * fade).toInt()
                glow?.let { canvas.drawBitmap(it, null, arc, glowPaint) }
            }
            paint.alpha = (255 * fade).toInt()
            canvas.drawBitmap(gold, null, target, paint)
            if (since < FLASH) {
                paint.alpha = (230 * (1f - since.toFloat() / FLASH) * fade).toInt()
                bright?.let { canvas.drawBitmap(it, null, target, paint) }
            }
            // AMIN PLAYER, briefly.
            val word = smooth(window(now, impact + ms(WORD_FROM), impact + ms(WORD_FROM + WORD_IN)))
            wordPaint.alpha = (255 * word * fade).toInt()
            val wordY = cy + size * 0.62f + wordPaint.textSize * 0.9f
            canvas.drawText("AMIN PLAYER", cx, wordY, wordPaint)
            if (holding && !stopped) {
                val r = hf * 0.022f
                val y = wordY + hf * 0.075f
                arc.set(cx - r, y - r, cx + r, y + r)
                spinnerPaint.strokeWidth = r * 0.32f
                spinnerPaint.alpha = (220 * fade).toInt()
                canvas.drawArc(arc, ((now / 1_000_000L) % 1000L) * 0.36f, 270f, false, spinnerPaint)
            }
        }
        return false
    }

    companion object {
        val CHARCOAL = Color.rgb(20, 17, 20)
        const val RATE = 44100
        // Timeline in milliseconds (about 2.1 s), then a 380 ms fade into the home page.
        const val MOULD_IN = 260L
        const val FILL_FROM = 180L
        const val FILL = 1050L             // without sound: the mould is full about 1.23 s in
        const val LEAD = 1000L             // silence before the strike in the track (the fill time)
        const val EXPECTED_LATENCY = 80L   // first guess of the audio output delay, replaced by the measurement
        const val MEASURE_LIMIT = 600L
        const val DISPLAY_LEAD = 16L
        const val FLASH = 200L
        const val GLOW = 600L
        const val WORD_FROM = 120L
        const val WORD_IN = 260L
        const val HOLD = 820L              // from the strike to the hand-over
        const val EXIT = 380L

        /** Reads the 16-bit mono PCM of a WAV resource (the strike sound). */
        fun pcm(context: Context, id: Int): ShortArray? = runCatching {
            val bytes = context.resources.openRawResource(id).use { it.readBytes() }
            var i = 12
            while (i + 8 <= bytes.size) {
                val tag = String(bytes, i, 4, Charsets.US_ASCII)
                val len = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (tag == "data") {
                    val n = min(len, bytes.size - i - 8) / 2
                    val out = ShortArray(n)
                    ByteBuffer.wrap(bytes, i + 8, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
                    return@runCatching out
                }
                i += 8 + len + (len and 1)
            }
            null
        }.getOrNull()
    }
}
