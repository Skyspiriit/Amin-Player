package com.enigma.farsilandtv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout

/**
 * Startup intro "The Golden Forge" (1.36): a pre-rendered 3D film (res/raw/intro_forge_video.mp4, 1080p, about 3.4 s).
 * A great war maul strikes glowing metal on an anvil, sparks fly, and a golden shield bearing the Amin Player mark and
 * name rises out of the anvil and cools to gold. The box's hardware video decoder plays it, so it stays smooth while the
 * home page loads on the main thread.
 *
 * [onSound] fires when the film reaches [SOUND_AT_MS] (read from the player itself, so the strike in the sound lands on
 * the strike in the picture); [onFormed] fires when the film ends, or at once if it cannot play. While the home page is
 * still loading after that, the last frame stays with a small spinner.
 */
internal class IntroVideoView(
    context: Context,
    private val onSound: () -> Unit,
    private val onFormed: () -> Unit
) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private val texture = TextureView(context)
    private val spinner = Spinner(context)
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var soundDone = false
    private var formedDone = false
    private var stopped = false
    private var exiting = false
    private var started = 0L
    var holding = false
        private set

    private val watch = object : Runnable {
        override fun run() {
            val p = player ?: return
            val position = runCatching { p.currentPosition }.getOrDefault(0)
            if (!soundDone && position >= SOUND_AT_MS) { soundDone = true; onSound() }
            if (!formedDone) main.postDelayed(this, 8)
        }
    }

    init {
        setBackgroundColor(CHARCOAL)
        isFocusable = true
        contentDescription = "Amin Player"
        texture.surfaceTextureListener = this
        texture.isOpaque = false
        addView(texture, LayoutParams(-1, -1))
        spinner.visibility = View.GONE
        addView(spinner, LayoutParams(-1, -1))
    }

    /** The home page is ready or failed: nothing more to wait for. */
    fun stop() {
        stopped = true
        spinner.visibility = View.GONE
    }

    /** Fades the intro out over the page underneath, then calls [done]. */
    fun fadeOut(done: () -> Unit) {
        stop()
        if (exiting) return
        exiting = true
        animate().alpha(0f).setDuration(EXIT).withEndAction {
            release()
            done()
        }.start()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(st).also { surface = it }
        val uri = Uri.parse("android.resource://${context.packageName}/${R.raw.intro_forge_video}")
        try {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                setSurface(s)
                setVolume(0f, 0f)
                setOnPreparedListener {
                    started = SystemClock.uptimeMillis()
                    it.start()
                    main.post(watch)
                }
                setOnCompletionListener { formed() }
                setOnErrorListener { _, _, _ -> formed(); true }
                prepareAsync()
            }
        } catch (_: Exception) {
            formed()
        }
    }

    private fun formed() {
        if (formedDone) return
        formedDone = true
        holding = true
        if (!soundDone) soundDone = true
        Log.i("AminIntro", "film ended after ${if (started == 0L) 0 else SystemClock.uptimeMillis() - started} ms")
        if (!stopped) spinner.visibility = View.VISIBLE
        main.post(onFormed)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        return true
    }

    private fun release() {
        main.removeCallbacks(watch)
        runCatching { player?.release() }
        player = null
        runCatching { surface?.release() }
        surface = null
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    /** A small gold spinner under the shield while the home page is still loading. */
    private class Spinner(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.rgb(242, 181, 68)
        }
        private val arc = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = height * 0.022f
            val cx = width / 2f
            val cy = height * 0.93f
            arc.set(cx - r, cy - r, cx + r, cy + r)
            paint.strokeWidth = r * 0.32f
            paint.alpha = 220
            canvas.drawArc(arc, (SystemClock.uptimeMillis() % 1000L) * 0.36f, 270f, false, paint)
            postInvalidateOnAnimation()
        }
    }

    companion object {
        val CHARCOAL = Color.rgb(20, 17, 20)
        /** The hammer strikes 1.18 s into the film; the sound's strike is 400 ms into the file, plus ~30 ms output latency. */
        const val IMPACT_MS = 1180
        const val SOUND_LEAD_MS = 430
        const val SOUND_AT_MS = IMPACT_MS - SOUND_LEAD_MS
        const val EXIT = 380L
    }
}
