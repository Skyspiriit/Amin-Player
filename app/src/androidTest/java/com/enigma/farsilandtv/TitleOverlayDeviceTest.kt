package com.enigma.farsilandtv

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Deterministic catalogue/player fixtures, not a claim about live video or regional connectivity. */
class TitleOverlayDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val episode = "https://farsiland.com/episodes/overlay-fixture-ep14/"
    private val series = "https://farsiland.com/tvshows/overlay-fixture/"

    private fun activity(): MainActivity {
        context.getSharedPreferences("playback_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("intro_animation", false).putBoolean("intro_sound", false).apply()
        return instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    }

    private fun field(activity: MainActivity, key: String) = MainActivity::class.java.getDeclaredField(key).apply { isAccessible = true }
    private fun web(activity: MainActivity) = field(activity, "web").get(activity) as WebView
    private fun eval(activity: MainActivity, body: String): JSONObject {
        val done = CountDownLatch(1)
        var value = "{}"
        instrumentation.runOnMainSync {
            web(activity).evaluateJavascript("(function(){try{$body}catch(e){return {error:String(e)}}})()") {
                value = it; done.countDown()
            }
        }
        assertTrue("WebView callback", done.await(5, TimeUnit.SECONDS))
        return JSONObject(value)
    }
    private fun await(message: String, seconds: Int = 12, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + seconds * 1000L
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; Thread.sleep(100) }
        fail(message)
    }
    private fun load(activity: MainActivity, url: String, body: String) {
        val marker = System.nanoTime().toString()
        instrumentation.runOnMainSync {
            val view = web(activity)
            view.stopLoading()
            val html = """<!doctype html><html lang="fa"><head>
                <meta name="viewport" content="width=device-width, initial-scale=1"><title>Fixture</title>
                </head><body data-overlay-fixture="$marker"><script>
                window.addEventListener('message',function(e){try{
                  var c=JSON.parse(e.data);if(c.app==='AminPlayer')document.body.dataset.overlayBridge='ready';
                }catch(ignored){}});
                </script>$body</body></html>"""
            val client = view.webViewClient
            // A real HTTPS navigation and the production lifecycle/bridge, with only the fixture
            // response supplied locally. loadDataWithBaseURL has a different callback lifecycle.
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(web: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (request.isForMainFrame && request.url.toString() == url)
                        WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
                    else client.shouldInterceptRequest(web, request)
                override fun shouldOverrideUrlLoading(web: WebView, request: WebResourceRequest) = client.shouldOverrideUrlLoading(web, request)
                override fun onPageStarted(web: WebView, page: String, icon: Bitmap?) = client.onPageStarted(web, page, icon)
                override fun onPageCommitVisible(web: WebView, page: String) = client.onPageCommitVisible(web, page)
                override fun onPageFinished(web: WebView, page: String) = client.onPageFinished(web, page)
                override fun doUpdateVisitedHistory(web: WebView, page: String, reload: Boolean) = client.doUpdateVisitedHistory(web, page, reload)
            }
            view.loadUrl(url)
        }
        await("TV script/native bridge did not initialise for fixture") {
            eval(activity, "return {ok:!!window.FarsiLandTv && document.body.dataset.overlayFixture==='$marker' && document.body.dataset.overlayBridge==='ready'}").optBoolean("ok")
        }
    }

    @Test fun selectedEpisodeUsesItsOwnRowAndMovieUsesItsReleaseYear() {
        val activity = activity()
        try {
            load(activity, episode, """<div id="single"><h1 class="player-title">سریال آزمایشی SE02 EP14</h1>
                <div class="pag_episodes"><a href="$series" title="سریال آزمایشی"><span>ALL</span></a>
                <a href="https://farsiland.com/episodes/overlay-fixture-ep15/" title="EP15"><span>NEXT</span></a></div>
                <div class="se-c"><div class="se-q"><span class="se-t">۲</span></div><ul class="episodios">
                <li><div class="numerando">۲ - ۱۵</div><div class="episodiotitle"><a href="https://farsiland.com/episodes/overlay-fixture-ep15/">قسمت بعدی</a></div></li>
                <li><div class="numerando">۲ - ۱۴</div><div class="episodiotitle"><a href="$episode">یک روز تازه</a></div></li></ul></div></div>""")
            val info = eval(activity, "return window.FarsiLandTv.watchInfo()").getJSONObject("titleInfo")
            assertEquals("سریال آزمایشی", info.getString("title"))
            assertEquals(2, info.getInt("season")); assertEquals(14, info.getInt("episode"))
            assertEquals("یک روز تازه", info.getString("episodeTitle"))
            val parsed = PlaybackTitle.fromJson(episode, info)!!
            assertEquals(14, parsed.episode)
            load(activity, "https://farsiland.com/movies/overlay-fixture/", """<div id="single">
                <div class="sheader"><div class="data"><h1>Fixture Movie</h1><h3>The Roof / سقف</h3>
                <meta itemprop="datePublished" content="2026-11-25"><div class="extra"><span class="date">May 12, 1988</span></div>
                </div></div></div>""")
            val movie = eval(activity, "return window.FarsiLandTv.watchInfo()").getJSONObject("titleInfo")
            assertEquals("سقف", movie.getString("title"))
            assertEquals(1988, movie.getInt("year"))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun metadataRoundTripPreservesContinueWatchingAndNeverCopiesOldEpisodeNumbers() {
        val scoped = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("overlay_test_$name", mode)
        }
        val prefs = scoped.getSharedPreferences("watch_history", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val legacy = WatchItem(episode, "قسمت انتخاب‌شده", null, null, LibraryTitle(series, "سریال", null))
            val title = PlaybackTitle("episode", "سریال", 2, 14, "یک روز تازه")
            val history = PlaybackHistory(scoped)
            history.remember(WatchProgress(legacy, 30, 600, 480))
            val before = history.forHome().toString()
            history.remember(WatchProgress(legacy.copy(titleInfo = title), 30, 600, 480))
            assertEquals(before, history.forHome().toString())
            assertEquals(title, PlaybackHistory(scoped).find(episode)?.item?.titleInfo)
            assertNull(NextEpisode("https://farsiland.com/episodes/next-season-ep1/", "EP1", true)
                .asWatchItem(null, legacy.series).titleInfo)
            assertNull(PlaybackTitle.fromJson("https://evil.test/episodes/test/", title.json()))
            val malformed = title.json().put("season", 1.9).put("episode", "14")
            assertNull(PlaybackTitle.fromJson(episode, malformed)?.season)
            assertNull(PlaybackTitle.fromJson(episode, malformed)?.episode)
            assertNull(WatchItem.fromPage(episode, legacy.json())?.titleInfo)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun remoteShowsReadableUnfocusableOverlayWhichFadesAndRespectsBack() {
        val activity = activity()
        try {
            val long = "سریال خاطره‌های یک روز بسیار طولانی در شهر و خانه‌های قدیمی — A Long Mixed Persian English Title"
            val info = PlaybackTitle("episode", long, 2, 14, "دیدار دوباره در یک روز روشن")
            instrumentation.runOnMainSync {
                field(activity, "watchItem").set(activity, WatchItem(episode, "EP14", null, null,
                    LibraryTitle(series, long, null), info))
                field(activity, "playbackReturn").set(activity, episode)
            }
            load(activity, "https://farsiplex.com/smart-play", """<div id="fixture-player" class="jwplayer" data-fl-full
                style="position:fixed;inset:0;width:100vw;height:100vh;background:white"><video style="width:100%;height:100%;background:white"></video></div>""")
            await("Player controls absent") { eval(activity,"return {ok:!!document.querySelector('#fl-play')}").optBoolean("ok") }
            // Production's native origin-scoped message channel carries the stored selected title.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            await("Title overlay not shown") { eval(activity,"return {ok:!!document.querySelector('.fl-title-visible')}").optBoolean("ok") }
            val view = eval(activity, """var p=document.querySelector('.fl-playback-title'), n=p.querySelector('.fl-title-name'),
                d=p.querySelector('.fl-title-detail'), b=document.querySelector('.fl-player-controls');
                return {title:n.textContent, detail:d.textContent, color:getComputedStyle(n).color,
                gold:getComputedStyle(d).color, pointer:getComputedStyle(p).pointerEvents,
                focus:document.activeElement.id, titleFocusable:!!p.querySelector('a,button,input,[tabindex]'),
                bottom:p.getBoundingClientRect().bottom, controls:b.getBoundingClientRect().top};""")
            assertEquals(long, view.getString("title")); assertEquals("فصل ۲ · قسمت ۱۴", view.getString("detail"))
            assertEquals("rgb(255, 255, 255)", view.getString("color"))
            assertEquals("rgb(255, 218, 131)", view.getString("gold"))
            assertEquals("none", view.getString("pointer")); assertFalse(view.getBoolean("titleFocusable"))
            assertTrue("Overlay overlaps controls", view.getDouble("bottom") < view.getDouble("controls"))
            Thread.sleep(250)
            instrumentation.runOnMainSync { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(context.getExternalFilesDir(null), "playback-title-bright.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }; bitmap.recycle()
            }
            Thread.sleep(5350)
            assertEquals("true", eval(activity,"return {hidden:document.querySelector('.fl-playback-title').getAttribute('aria-hidden')}").getString("hidden"))
            assertEquals(view.getString("focus"), eval(activity,"return {focus:document.activeElement.id}").getString("focus"))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            await("Overlay could not be shown again") { eval(activity,"return {ok:!!document.querySelector('.fl-title-visible')}").optBoolean("ok") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Back did not hide title and exit fullscreen") { eval(activity,"return {ok:!document.querySelector('[data-fl-full]') && !document.querySelector('.fl-title-visible')}").optBoolean("ok") }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
