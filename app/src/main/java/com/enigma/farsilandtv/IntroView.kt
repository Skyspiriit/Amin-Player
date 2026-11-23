package com.enigma.farsilandtv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
import java.util.Random
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Startup intro "The Golden Forge" (1.35), about 2.85 s. On charcoal, a piece of glowing metal rests on an anvil; a
 * blacksmith's hammer rises and strikes it; sparks fly with a flash and a small shake; the hot metal rises and turns
 * into the existing gold Amin Player mark; a light sweep crosses the mark and reveals AMIN PLAYER. Then the view
 * waits for the home page and shows a small spinner while it is still loading.
 *
 * It draws on its own thread into a surface above the page, so the home page loading on the main thread at the same
 * time cannot make it stutter. [onSound] runs on that thread [SOUND_LEAD] ms before the strike, because the sound
 * starts with the hammer's whoosh; the strike in the sound file lands exactly on the hammer's impact.
 */
internal class IntroView(
    context: Context,
    private val mark: Bitmap,
    private val reducedMotion: Boolean,
    private val onSound: () -> Unit,
    private val onFormed: () -> Unit
) : SurfaceView(context), SurfaceHolder.Callback {
    private val main = Handler(Looper.getMainLooper())
    // The timeline starts with the first frame, after the art is prepared, so a slow start never skips the opening.
    @Volatile private var start = 0L
    @Volatile private var soundDone = false
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

    private val res = context.resources
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val brightPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignettePaint = Paint()
    private val flashPaint = Paint()
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.22f
        color = Color.rgb(246, 214, 138)
    }
    private val wordBright = Paint(wordPaint).apply { color = Color.rgb(255, 246, 220) }
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(242, 181, 68)
    }
    private val matrix = Matrix()
    private val target = RectF()
    private val rect = RectF()
    private val band = Path()

    // Prepared once (on the drawing thread) so each frame only places bitmaps.
    private var face: Bitmap? = null
    private var bright: Bitmap? = null
    private var glow: Bitmap? = null
    private var anvil: Bitmap? = null
    private var hammer: Bitmap? = null
    private var ingot: Bitmap? = null
    private var faceSize = 1f
    private var frames = 0

    // Scene geometry (pixels), set in prepare().
    private var cx = 0f
    private var logoY = 0f
    private var anvilRect = RectF()
    private var ingotRect = RectF()
    private var hammerScale = 1f
    private var pivotX = 0f
    private var pivotY = 0f

    // Sparks: plain arrays, spawned once at the strike.
    private val sparkX = FloatArray(SPARKS)
    private val sparkY = FloatArray(SPARKS)
    private val sparkVx = FloatArray(SPARKS)
    private val sparkVy = FloatArray(SPARKS)
    private val sparkLife = FloatArray(SPARKS)

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

    private fun load(id: Int, width: Int): Bitmap? = runCatching {
        val raw = BitmapFactory.decodeResource(res, id) ?: return null
        val height = max(1, (raw.height * width.toFloat() / raw.width).toInt())
        Bitmap.createScaledBitmap(raw, max(1, width), height, true).also { if (it !== raw) raw.recycle() }
    }.getOrNull()

    private fun prepare() {
        val wf = w.toFloat()
        val hf = h.toFloat()
        faceSize = min(w, h) * 0.36f
        val side = max(1, faceSize.toInt())
        val scaled = Bitmap.createScaledBitmap(mark, side, side, true)
        face = scaled
        bright = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { tinted ->
            Canvas(tinted).apply {
                drawBitmap(scaled, 0f, 0f, null)
                drawColor(Color.rgb(255, 214, 132), PorterDuff.Mode.SRC_ATOP)
            }
        }
        val glowSide = max(2, (faceSize * 1.6f).toInt())
        glow = Bitmap.createBitmap(glowSide, glowSide, Bitmap.Config.ARGB_8888).also { halo ->
            val r = glowSide / 2f
            Canvas(halo).drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(r, r, r, intArrayOf(Color.argb(170, 255, 196, 92), Color.argb(120, 242, 160, 52),
                    Color.argb(0, 242, 150, 40)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            })
        }

        // Scene: the anvil's working face sits at 64 % of the height, its middle under the centre of the screen.
        cx = wf / 2f
        logoY = hf * 0.44f
        val anvilW = hf * 0.74f
        anvil = load(R.drawable.forge_anvil, anvilW.toInt())
        val anvilH = anvilW * 520f / 900f
        val faceY = hf * 0.64f
        val anvilLeft = cx - anvilW * 0.56f
        val anvilTop = faceY - anvilH * (116f / 520f)
        anvilRect.set(anvilLeft, anvilTop, anvilLeft + anvilW, anvilTop + anvilH)
        // The metal: its bar (y 70..130 of 200) rests on the face.
        val ingotW = anvilW * 0.42f
        ingot = load(R.drawable.forge_ingot, ingotW.toInt())
        val ingotH = ingotW * 200f / 420f
        val ingotTop = faceY - ingotH * (130f / 200f)
        ingotRect.set(cx - ingotW / 2, ingotTop, cx + ingotW / 2, ingotTop + ingotH)
        // The hammer: at 0° its striking face (x 112, y 262 of 760x300) rests on the top of the bar.
        val hammerW = anvilW * 0.86f
        hammer = load(R.drawable.forge_hammer, hammerW.toInt())
        hammerScale = hammerW / 760f
        val barTop = ingotTop + ingotH * (70f / 200f)
        pivotX = cx + (742f - 112f) * hammerScale
        pivotY = barTop - (262f - 150f) * hammerScale

        lightPaint.shader = RadialGradient(cx, faceY, max(1f, hf * 0.62f),
            intArrayOf(Color.argb(150, 120, 66, 24), Color.argb(60, 70, 40, 20), Color.argb(0, 20, 17, 20)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        vignettePaint.shader = RadialGradient(cx, hf * 0.5f, max(1f, wf * 0.72f),
            intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(170, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        wordPaint.textSize = hf * 0.054f
        wordBright.textSize = wordPaint.textSize

        // Sparks fly up and out from the point of impact in a wide fan; a few skim sideways along the anvil.
        val random = Random(1351)
        for (i in 0 until SPARKS) {
            val sideways = i % 7 == 0
            val angle = if (sideways) (if (i % 2 == 0) -0.12 else Math.PI + 0.12) + random.nextGaussian() * 0.08
                else -Math.PI / 2 + random.nextGaussian() * 0.62
            val speed = hf * (0.55f + random.nextFloat() * 1.25f) * (if (sideways) 1.25f else 1f)
            sparkX[i] = cx + (random.nextFloat() - 0.5f) * ingotW * 0.25f
            sparkY[i] = barTop
            sparkVx[i] = (cos(angle) * speed).toFloat()
            sparkVy[i] = (sin(angle) * speed).toFloat()
            sparkLife[i] = 420f + random.nextFloat() * 620f
        }
    }

    private fun loop() {
        if (face == null || faceSize != min(w, h) * 0.36f) prepare()
        if (start == 0L) start = SystemClock.uptimeMillis()
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
    private fun easeIn(x: Float) = x.coerceIn(0f, 1f).pow(3)
    private fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    private fun window(now: Long, from: Long, to: Long) = ((now - from).toFloat() / (to - from)).coerceIn(0f, 1f)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Draws one frame; returns true when the fade-out has completed. */
    private fun drawFrame(canvas: Canvas): Boolean {
        val clock = SystemClock.uptimeMillis()
        val now = if (reducedMotion) END else clock - start
        val fade = if (exitStart == 0L) 1f
            else if (reducedMotion) 0f else 1f - ((clock - exitStart).toFloat() / EXIT).coerceIn(0f, 1f)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (fade <= 0f) return true
        val face = face ?: return false
        if (!soundDone && now >= SOUND_AT) { soundDone = true; onSound() }
        frames++
        if (!formedDone && now >= END) {
            formedDone = true
            holding = true
            Log.i("AminIntro", "drew $frames frames in $now ms")
            main.post(onFormed)
        }

        val wf = w.toFloat()
        val hf = h.toFloat()
        val since = now - IMPACT
        canvas.drawColor(Color.argb((255 * fade).toInt(), 20, 17, 20))

        // A small, decaying shake for the strike (the whole scene, not the background).
        val shake = if (since in 0..SHAKE) (1f - since.toFloat() / SHAKE).pow(2) * hf * 0.010f else 0f
        val saved = canvas.save()
        if (shake > 0f) canvas.translate(shake * sin(since * 0.21f), shake * cos(since * 0.17f) * 0.6f)

        // Forge light: warm glow from the fire, flickering slightly, stronger at the strike, gone as the mark forms.
        val scene = ease(window(now, 0, FADE_IN))
        val forgeOut = 1f - smooth(window(now, ANVIL_OUT_FROM, ANVIL_OUT_TO))
        val flicker = 0.92f + 0.08f * sin(now * 0.013f) * sin(now * 0.0071f)
        val boost = if (since in 0..600) 1f + 0.6f * (1f - since / 600f) else 1f
        lightPaint.alpha = (255 * scene * min(1f, flicker * boost) * max(forgeOut, 0.35f) * fade).toInt()
        canvas.drawRect(0f, 0f, wf, hf, lightPaint)

        // Anvil (sinks a little and fades once the metal has risen).
        anvil?.let {
            val sink = hf * 0.04f * smooth(window(now, ANVIL_OUT_FROM, ANVIL_OUT_TO))
            rect.set(anvilRect.left, anvilRect.top + sink, anvilRect.right, anvilRect.bottom + sink)
            bitmapPaint.alpha = (255 * scene * forgeOut * fade).toInt()
            if (bitmapPaint.alpha > 0) canvas.drawBitmap(it, null, rect, bitmapPaint)
        }

        // The metal: glowing on the anvil, white-hot at the strike, then rising and turning into the mark.
        val rise = smooth(window(now, RISE_FROM, RISE_TO))
        val ingotFade = 1f - smooth(window(now, INGOT_OUT_FROM, INGOT_OUT_TO))
        val logoIn = smooth(window(now, LOGO_IN_FROM, LOGO_IN_TO))
        val icx = cx
        val icy = lerp(ingotRect.centerY(), logoY, rise)
        val iw = lerp(ingotRect.width(), faceSize * 1.1f, rise)
        val ih = lerp(ingotRect.height(), faceSize * 0.5f, rise)
        ingot?.let {
            if (ingotFade > 0f) {
                rect.set(icx - iw / 2, icy - ih / 2, icx + iw / 2, icy + ih / 2)
                val heat = 0.85f + 0.15f * sin(now * 0.009f)
                bitmapPaint.alpha = (255 * scene * ingotFade * heat * fade).toInt()
                canvas.drawBitmap(it, null, rect, bitmapPaint)
                // White-hot after the strike.
                if (since in 0..700) {
                    glowPaint.alpha = (200 * (1f - since / 700f) * ingotFade * fade).toInt()
                    canvas.drawBitmap(it, null, rect, glowPaint)
                }
            }
        }

        // Shock glow behind the point of impact.
        if (since in 0..GLOW) {
            val p = since.toFloat() / GLOW
            val half = ingotRect.width() * (0.4f + 1.1f * ease(p))
            glowPaint.alpha = (230 * (1 - p).pow(1.6f) * fade).toInt()
            rect.set(cx - half, ingotRect.centerY() - half * 0.75f, cx + half, ingotRect.centerY() + half * 0.75f)
            glow?.let { canvas.drawBitmap(it, null, rect, glowPaint) }
        }

        // The mark forming out of the rising metal: white-gold first, settling into its own gold.
        if (logoIn > 0f) {
            val size = faceSize * lerp(0.5f, 1f, ease(window(now, LOGO_IN_FROM, LOGO_SETTLE)))
            target.set(cx - size / 2, icy - size / 2, cx + size / 2, icy + size / 2)
            if (now >= LOGO_IN_FROM) {
                val halo = 1f - smooth(window(now, LOGO_IN_TO, LOGO_SETTLE + 300))
                if (halo > 0f) {
                    val half = size * 0.85f
                    rect.set(cx - half, icy - half, cx + half, icy + half)
                    glowPaint.alpha = (170 * halo * logoIn * fade).toInt()
                    glow?.let { canvas.drawBitmap(it, null, rect, glowPaint) }
                }
            }
            val gold = smooth(window(now, LOGO_GOLD_FROM, LOGO_SETTLE))
            bitmapPaint.alpha = (255 * logoIn * gold * fade).toInt()
            canvas.drawBitmap(face, null, target, bitmapPaint)
            val hot = logoIn * 0.9f * (1f - gold)
            bright?.let {
                brightPaint.alpha = (255 * hot * fade).toInt()
                if (brightPaint.alpha > 0) canvas.drawBitmap(it, null, target, brightPaint)
            }
        }

        // The hammer: rises, winds up, swings down fast (with a short motion trail), strikes, rebounds and fades.
        hammer?.let { drawHammer(canvas, it, now, scene, fade) }

        // Sparks.
        if (since in 0..SPARK_END) drawSparks(canvas, since.toFloat(), hf, fade)

        canvas.restoreToCount(saved)

        // Flash at the strike.
        if (since in 0..FLASH) {
            flashPaint.color = Color.argb((120 * (1f - since.toFloat() / FLASH) * fade).toInt(), 255, 226, 170)
            canvas.drawRect(0f, 0f, wf, hf, flashPaint)
        }

        // A soft light sweep across the finished mark.
        val sweep = window(now, SWEEP_FROM, SWEEP_TO)
        val sheet = bright
        if (sheet != null && sweep in 0.001f..0.999f && logoIn > 0f) {
            val size = target.width()
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
                val clip = canvas.save()
                canvas.clipPath(band)
                brightPaint.alpha = (alpha * fade).toInt()
                canvas.drawBitmap(sheet, null, target, brightPaint)
                canvas.restoreToCount(clip)
            }
        }

        // AMIN PLAYER, revealed left to right by the same light.
        val word = window(now, WORD_FROM, WORD_TO)
        val wordY = logoY + faceSize * 0.62f + wordPaint.textSize * 0.9f
        if (word > 0f) {
            val textW = wordPaint.measureText(WORD)
            val left = cx - textW / 2 - wordPaint.textSize
            val edge = left + (textW + wordPaint.textSize * 2) * ease(word)
            val clip = canvas.save()
            canvas.clipRect(left, wordY - wordPaint.textSize * 1.2f, edge, wordY + wordPaint.textSize * 0.4f)
            wordPaint.alpha = (255 * fade).toInt()
            canvas.drawText(WORD, cx, wordY, wordPaint)
            canvas.restoreToCount(clip)
            if (word < 1f) {
                val glint = wordPaint.textSize * 1.4f
                val g = canvas.save()
                canvas.clipRect(edge - glint, wordY - wordPaint.textSize * 1.2f, edge, wordY + wordPaint.textSize * 0.4f)
                wordBright.alpha = (230 * fade).toInt()
                canvas.drawText(WORD, cx, wordY, wordBright)
                canvas.restoreToCount(g)
            }
        }

        vignettePaint.alpha = (255 * fade).toInt()
        canvas.drawRect(0f, 0f, wf, hf, vignettePaint)

        // Still loading after the intro: a small spinner.
        if (holding && !stopped) {
            val r = hf * 0.022f
            val y = wordY + hf * 0.075f
            rect.set(cx - r, y - r, cx + r, y + r)
            spinnerPaint.strokeWidth = r * 0.32f
            spinnerPaint.alpha = (220 * fade).toInt()
            val turn = if (reducedMotion) 0f else ((clock - start) % 1000L) * 0.36f
            canvas.drawArc(rect, turn, 270f, false, spinnerPaint)
        }
        return false
    }

    private fun hammerAngle(now: Long): Float = when {
        now < WINDUP_FROM -> RAISED
        now < SWING_FROM -> lerp(RAISED, WOUND, ease(window(now, WINDUP_FROM, SWING_FROM)))
        now < IMPACT -> lerp(WOUND, 0f, easeIn(window(now, SWING_FROM, IMPACT)))
        else -> REBOUND * ease(window(now, IMPACT, IMPACT + 260)) - REBOUND * 0.3f * smooth(window(now, IMPACT + 260, HAMMER_OUT_TO))
    }

    private fun drawHammer(canvas: Canvas, bitmap: Bitmap, now: Long, scene: Float, fade: Float) {
        val appear = ease(window(now, HAMMER_IN_FROM, HAMMER_IN_TO))
        val leave = 1f - smooth(window(now, HAMMER_OUT_FROM, HAMMER_OUT_TO))
        val alpha = scene * appear * leave * fade
        if (alpha <= 0f) return
        val angle = hammerAngle(now)
        // A short trail during the fast part of the swing reads as motion blur.
        if (now in (SWING_FROM + 60) until IMPACT) {
            for ((step, trail) in TRAIL) {
                placeHammer(hammerAngle(now - step))
                bitmapPaint.alpha = (255 * alpha * trail).toInt()
                canvas.drawBitmap(bitmap, matrix, bitmapPaint)
            }
        }
        placeHammer(angle)
        bitmapPaint.alpha = (255 * alpha).toInt()
        canvas.drawBitmap(bitmap, matrix, bitmapPaint)
    }

    /** Positions the hammer bitmap: scaled, its pivot (the end of the handle) on [pivotX], [pivotY], rotated. */
    private fun placeHammer(degrees: Float) {
        val px = 742f * hammerScale
        val py = 150f * hammerScale
        matrix.reset()
        matrix.postTranslate(pivotX - px, pivotY - py)
        matrix.postRotate(degrees, pivotX, pivotY)
    }

    private fun drawSparks(canvas: Canvas, since: Float, hf: Float, fade: Float) {
        val gravity = hf * 1.9f
        val t = since / 1000f
        for (i in 0 until SPARKS) {
            val life = sparkLife[i]
            if (since > life) continue
            val p = since / life
            val drag = 1f / (1f + 1.6f * t)
            val x = sparkX[i] + sparkVx[i] * t * drag
            val y = sparkY[i] + sparkVy[i] * t * drag + 0.5f * gravity * t * t
            val vx = sparkVx[i] * drag
            val vy = sparkVy[i] * drag + gravity * t
            val speed = sqrt(vx * vx + vy * vy).coerceAtLeast(1f)
            val streak = min(hf * 0.05f, speed * 0.022f)
            val heat = 1f - p
            // White-yellow when fresh, orange, then deep red as they cool.
            val r = 255
            val g = (90 + 160 * heat).toInt().coerceIn(0, 255)
            val b = (30 + 170 * heat * heat).toInt().coerceIn(0, 255)
            sparkPaint.color = Color.argb((255 * heat.pow(0.7f) * fade).toInt(), r, g, b)
            sparkPaint.strokeWidth = hf * (0.0022f + 0.0026f * heat)
            canvas.drawLine(x, y, x - vx / speed * streak, y - vy / speed * streak, sparkPaint)
        }
    }

    companion object {
        val CHARCOAL = Color.rgb(20, 17, 20)
        private const val WORD = "AMIN PLAYER"
        private const val SPARKS = 96
        private val BANDS = listOf(0.34f to 45, 0.22f to 70, 0.11f to 95)
        private val TRAIL = listOf(14L to 0.28f, 28L to 0.16f, 44L to 0.08f)
        // Hammer angles (degrees, clockwise lifts the head).
        private const val RAISED = 40f
        private const val WOUND = 49f
        private const val REBOUND = 18f
        // Timeline in milliseconds (about 2.85 s), then a 380 ms fade into the home page.
        const val FADE_IN = 450L
        const val HAMMER_IN_FROM = 150L
        const val HAMMER_IN_TO = 650L
        const val WINDUP_FROM = 450L
        const val SWING_FROM = 1000L
        const val IMPACT = 1180L
        /** The sound file's strike is 400 ms in (its whoosh comes first); 30 ms more covers output latency. */
        const val SOUND_LEAD = 430L
        const val SOUND_AT = IMPACT - SOUND_LEAD
        const val SHAKE = 240L
        const val FLASH = 130L
        const val GLOW = 520L
        const val SPARK_END = 1100L
        const val HAMMER_OUT_FROM = 1320L
        const val HAMMER_OUT_TO = 1620L
        const val RISE_FROM = 1300L
        const val RISE_TO = 1900L
        const val INGOT_OUT_FROM = 1420L
        const val INGOT_OUT_TO = 1800L
        const val LOGO_IN_FROM = 1450L
        const val LOGO_IN_TO = 1800L
        const val LOGO_GOLD_FROM = 1580L
        const val LOGO_SETTLE = 2000L
        const val ANVIL_OUT_FROM = 1400L
        const val ANVIL_OUT_TO = 1950L
        const val SWEEP_FROM = 1950L
        const val SWEEP_TO = 2550L
        const val WORD_FROM = 2100L
        const val WORD_TO = 2650L
        const val END = 2850L
        const val EXIT = 380L
    }
}
