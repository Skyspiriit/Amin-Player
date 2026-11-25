package com.enigma.farsilandtv

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit, network-dependent test: run this class separately from deterministic fixtures. */
class TitleOverlayLiveDeviceTest {
    @Test fun selectedEpisodeSurvivesRealProviderHandoffAndRemoteControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Run explicitly with -e aminLivePlayback true", InstrumentationRegistry.getArguments().getString("aminLivePlayback") == "true")
        val context = instrumentation.targetContext
        val episode = InstrumentationRegistry.getArguments().getString("episodeUrl")
            ?: "https://farsiland.com/episodes/roshan-ep12/"
        require(PlaybackSites.isCatalogue(episode) && "/episodes/" in episode)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val evidence = mutableListOf<String>()
        fun eval(body: String): JSONObject {
            val done = CountDownLatch(1)
            var reply = "{}"
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("endIntro", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                val web = MainActivity::class.java.getDeclaredField("web").apply { isAccessible = true }.get(activity) as WebView
                web.evaluateJavascript("(function(){try{$body}catch(e){return {jsError:String(e)}}})()") { reply = it; done.countDown() }
            }
            assertTrue("WebView callback timed out", done.await(8, TimeUnit.SECONDS))
            return runCatching { JSONObject(reply) }.getOrDefault(JSONObject())
        }
        fun waitFor(label: String, seconds: Int = 20, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + seconds * 1000L
            while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; Thread.sleep(350) }
            fail(label)
        }
        fun remote(key: Int) = instrumentation.sendKeyDownUpSync(key)
        try {
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("load", String::class.java).apply { isAccessible = true }.invoke(activity, episode)
            }
            waitFor("Live selected episode page did not load", 130) {
                eval("return {ok:!!window.FarsiLandTv && !!document.querySelector('#fl-episode-play')}").optBoolean("ok")
            }
            val selected = eval("return window.FarsiLandTv.watchInfo()").getJSONObject("titleInfo")
            assertTrue(selected.getString("title").isNotBlank())
            assertTrue("Episode facts unavailable", selected.optInt("episode") > 0)
            evidence += "Selected catalogue metadata: $selected"
            eval("document.querySelector('#fl-episode-play').click();return {}")
            var lastPlay = 0L
            waitFor("Live provider video did not start", 150) {
                val state = eval("var v=document.querySelector('video');return {playing:!!v&&!v.paused&&v.currentTime>0&&Number.isFinite(v.duration)&&v.duration>300,play:!!document.querySelector('#fl-play'),host:location.hostname}")
                if (!state.optBoolean("playing") && state.optBoolean("play") && SystemClock.elapsedRealtime()-lastPlay>12000) {
                    eval("document.querySelector('#fl-play').click();return {}")
                    lastPlay=SystemClock.elapsedRealtime()
                }
                if (state.optBoolean("playing")) { evidence += "Provider host: ${state.optString("host")}"; true } else false
            }
            val first = eval("var v=document.querySelector('video');return {time:v.currentTime,duration:v.duration,height:v.videoHeight}")
            waitFor("Playback did not advance continuously", 45) {
                eval("var v=document.querySelector('video');return {ok:!!v&&!v.paused&&v.currentTime>=${first.getDouble("time")+15}}").optBoolean("ok")
            }
            evidence += "Actual video advanced at least 15 seconds; duration=${first.getDouble("duration").toInt()}s, height=${first.getInt("height")}"
            remote(KeyEvent.KEYCODE_DPAD_UP)
            waitFor("Remote did not reveal title") { eval("return {ok:!!document.querySelector('.fl-title-visible')}").optBoolean("ok") }
            val caption = eval("return {title:document.querySelector('.fl-title-name').textContent,detail:document.querySelector('.fl-title-detail').textContent,font:document.fonts.check('700 32px \"Amin Vazirmatn\"')}")
            assertEquals(selected.getString("title"), caption.getString("title"))
            assertTrue("Bundled Persian font not ready", caption.getBoolean("font"))
            evidence += "Remote Up showed selected title, ${caption.getString("detail")}; bundled font ready"
            remote(KeyEvent.KEYCODE_MEDIA_PAUSE)
            waitFor("Pause failed") { eval("return {ok:document.querySelector('video').paused}").optBoolean("ok") }
            remote(KeyEvent.KEYCODE_MEDIA_PLAY)
            waitFor("Resume failed") { eval("return {ok:!document.querySelector('video').paused}").optBoolean("ok") }
            evidence += "Remote media Pause/Play passed"
            val quality = eval("var b=document.querySelector('.fl-quality-control');if(b&&!b.disabled&&!b.hidden)b.click();return {available:!!b&&!b.disabled&&!b.hidden}")
            if (quality.optBoolean("available")) {
                waitFor("Quality picker not shown") { eval("return {ok:!!document.querySelector('.fl-quality-overlay:not([hidden])')}").optBoolean("ok") }
                remote(KeyEvent.KEYCODE_BACK)
                waitFor("Back did not close quality picker") { eval("return {ok:!document.querySelector('.fl-quality-overlay:not([hidden])')}").optBoolean("ok") }
                evidence += "Existing quality picker and remote Back passed (quality unchanged)"
            } else evidence += "Provider did not expose quality control for this stream"
        } finally {
            File(context.getExternalFilesDir(null), "title-live-evidence.txt").writeText(evidence.joinToString("\n"))
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
