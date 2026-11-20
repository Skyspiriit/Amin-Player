package com.enigma.farsilandtv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var status: LinearLayout
    private lateinit var message: TextView
    private lateinit var retry: Button
    private lateinit var home: Button
    private lateinit var fallbackVpn: Button

    private val handler = Handler(Looper.getMainLooper())
    private val updater by lazy { AppUpdater(this, ::advanceUpdater) }
    private val watchHistory by lazy { PlaybackHistory(this) }
    private val favorites by lazy { Favorites(this) }
    private val vpnObserver: () -> Unit = { sendVpnState() }
    private val playbackSettings by lazy { getSharedPreferences("playback_settings", MODE_PRIVATE) }
    private var preferredQuality: Int? = null
    private var availableQualities = emptyList<Int>()
    private var activeUrl = HOME
    private val catalogueHistory = ArrayList<String>()
    private var playbackReturn: String? = null
    private var watchItem: WatchItem? = null
    private var previousEpisode: Pair<String, NextEpisode>? = null
    private var playbackLaunch: PlaybackLaunch? = null
    private var playbackPort: WebMessagePort? = null
    private var playerRemote = PlayerRemote.PAGE
    private var quality: Int? = null
    private var finishedEpisode = false
    private var resumed = false
    private var generation = 0
    private var ready = false
    private var failed = false
    private var backPending = false
    private var rendererGone = false
    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val timeout = Runnable {
        if (!ready && !failed && !isFinishing) {
            showError()
            web.stopLoading()
        }
    }

    private val navigationParts by lazy {
        val script = assets.open("tv-navigation.js").bufferedReader().use { it.readText() }
        val css = assets.open("tv.css").bufferedReader().use { it.readText() }
        @Suppress("DEPRECATION")
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        "($script)(${JSONObject.quote(css)}, ${JSONObject.quote(versionName)}"
    }

    // The colour theme is stored here, not in the page, so it also applies on the player site.
    private fun navigationScript(): String {
        val theme = playbackSettings.getString("theme", null)?.takeIf { it in THEMES } ?: THEMES.first()
        return "$navigationParts, ${JSONObject.quote(theme)}) && window.FarsiLandTv.watchInfo()"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferredQuality = videoQuality(playbackSettings.getInt("preferred_quality", 0))
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(11, 18, 32)) }
        createWebView()
        createStatus()
        setContentView(root)
        hideSystemBars()
        playbackReturn = savedInstanceState?.getString("playbackReturn")?.takeIf(::isWatchPage)
        savedInstanceState?.getStringArrayList("catalogueHistory")
            ?.filter(::isCatalogueSite)?.let(catalogueHistory::addAll)
        val history = savedInstanceState?.getBundle("history")?.let { web.restoreState(it) }
        val restoredUrl = history?.currentItem?.url
        if (restoredUrl != null && isSite(restoredUrl)) {
            activeUrl = restoredUrl
            showLoading()
        } else {
            openHome()
        }
    }

    private fun createWebView() {
        web = WebView(this).apply {
            setBackgroundColor(Color.rgb(11, 18, 32))
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
            }
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient = SiteClient()
            webChromeClient = TvChromeClient()
        }
        root.addView(web, 0, FrameLayout.LayoutParams(-1, -1))
    }

    private fun createStatus() {
        status = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(40), dp(24), dp(40), dp(24))
            setBackgroundColor(Color.rgb(11, 18, 32))
            isClickable = true
            isFocusableInTouchMode = true
        }
        message = TextView(this).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        status.addView(message, LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        retry = statusButton(R.string.retry) {
            val url = activeUrl
            if (rendererGone) {
                createWebView()
                rendererGone = false
                load(url)
            } else if (web.url == url) {
                showLoading()
                web.reload()
            } else {
                load(url)
            }
        }
        home = statusButton(R.string.home, ::openHome)
        fallbackVpn = statusButton(R.string.vpn_connect, ::openVpn)
        for (button in listOf(retry, home, fallbackVpn)) {
            actions.addView(button, LinearLayout.LayoutParams(-2, -2).apply {
                setMargins(dp(8), dp(20), dp(8), 0)
            })
        }
        status.addView(actions)
        root.addView(status, FrameLayout.LayoutParams(-1, -1))
    }

    private fun statusButton(label: Int, action: () -> Unit): Button = Button(this).apply {
        setText(label)
        textSize = 19f
        setTextColor(Color.WHITE)
        minHeight = dp(54)
        setPadding(dp(20), 0, dp(20), 0)
        fun shape(fill: Int, stroke: Int) = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(fill)
            setStroke(dp(3), stroke)
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(0xff334159.toInt(), 0xffffe180.toInt()))
            addState(intArrayOf(), shape(0xff23324a.toInt(), 0xff415570.toInt()))
        }
        setOnClickListener { action() }
    }

    private fun openVpn() {
        startActivity(Intent(this, VpnSetupActivity::class.java)
            .putExtra(VpnSetupActivity.CONNECT_REQUEST, true))
    }

    private fun load(url: String) {
        activeUrl = url.takeIf(::isSite) ?: HOME
        showLoading()
        web.loadUrl(activeUrl)
    }

    private data class PlaybackLaunch(
        val progress: WatchProgress,
        val fullscreen: Boolean,
        val playing: Boolean,
        val requestedQuality: Int?,
    )

    private enum class PlayerRemote { PAGE, CONTROLS, FRAME }

    private fun startPlayback(
        progress: WatchProgress,
        fullscreen: Boolean = true,
        playing: Boolean = true,
        requestedQuality: Int? = null,
    ) {
        playbackLaunch = PlaybackLaunch(progress, fullscreen, playing, requestedQuality)
        hideFullscreen()
        load(progress.item.url)
    }

    private fun closePlaybackPort() {
        playbackPort?.close()
        playbackPort = null
        playerRemote = PlayerRemote.PAGE
    }

    private fun connectPlayback(view: WebView, page: String, details: JSONObject) {
        val config = JSONObject()
        if (isHome(page)) {
            config.put("history", watchHistory.forHome()).put("legacyEpisodes", watchHistory.legacyEpisodes())
            config.put("vpn", vpnState())
        } else if (isFavoritesPage(page)) {
            config.put("favorites", favorites.forScreen())
        } else if (isSeriesPage(page)) {
            config.put("series", true)
        } else if (isWatchPage(page)) {
            val parsed = WatchItem.fromPage(page, details) ?: return
            val saved = watchHistory.find(parsed.url)?.item
            val item = parsed.copy(next = parsed.next ?: saved?.next, series = parsed.series ?: saved?.series)
            watchItem = item
            previousEpisode = watchUrl(details.optString("previous"))
                ?.takeIf { it != item.url && "/episodes/" in it && "/episodes/" in item.url }
                ?.let { item.url to NextEpisode(it, details.optString("previousTitle").trim().take(180), false) }
            val launch = playbackLaunch?.takeIf { it.progress.item.url == item.url }
            quality = launch?.progress?.quality
            availableQualities = emptyList()
            finishedEpisode = false
            if (launch != null) config.put("openPlayer", true)
            if (parsed.next == null && item.url.contains("/episodes/")) config.put("findNextSeason", true)
        } else if (isPlayerSite(page)) {
            val url = watchUrl(playbackReturn.orEmpty())
            val item = watchItem?.takeIf { it.url == url } ?: url?.let { watchHistory.find(it)?.item }
            watchItem = item
            config.put("player", true).put("next", item?.next?.title?.ifBlank { "قسمت بعدی" })
                .put("nextSeason", item?.next?.startsSeason == true)
                .put("previous", previousEpisode?.takeIf { it.first == item?.url }?.second?.title?.ifBlank { "قسمت قبلی" })
                .put("startVideo", item != null && resumed)
                .put("qualities", JSONArray(availableQualities))
                .put("preferredQuality", preferredQuality)
                .put("quality", quality)
            playbackLaunch?.takeIf { it.progress.item.url == url }?.let { launch ->
                config.put("resume", JSONObject().put("position", launch.progress.position)
                    .put("requestedQuality", launch.requestedQuality).put("fullscreen", launch.fullscreen)
                    .put("playing", launch.playing))
            }
        } else return

        titleUrl(page)?.let { title ->
            config.put("favorite", favorites.contains(title))
            val saved = watchHistory.resumeForTitle(title)
            config.put("detailResume", saved != null && (saved.position > 0 || isSeriesPage(page)))
            if (isSeriesPage(page)) config.put("seriesEpisode", watchHistory.latestForTitle(title)?.item?.url)
        }

        closePlaybackPort()
        val (appPort, pagePort) = view.createWebMessageChannel()
        playbackPort = appPort
        val pageGeneration = generation
        appPort.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(port: WebMessagePort, message: WebMessage) {
                if (view !== web || port !== playbackPort || pageGeneration != generation || !ready) return
                val data = message.data?.takeIf { it.length <= 4096 } ?: return
                runCatching { JSONObject(data) }.getOrNull()?.let { receivePlayback(page, it) }
            }
        })
        val origin = Uri.parse("https://${Uri.parse(page).encodedAuthority}")
        view.postWebMessage(WebMessage(config.put("app", "AminPlayer").toString(), arrayOf(pagePort)), origin)
    }

    private fun receivePlayback(page: String, message: JSONObject) {
        val event = message.optString("event")
        if (event == "theme") {
            message.optString("theme").takeIf { it in THEMES }?.let {
                playbackSettings.edit().putString("theme", it).apply()
            }
            return
        }
        if (isHome(page)) {
            when (event) {
                "vpn" -> if (resumed) {
                    openVpn()
                }
                "resume" -> if (resumed) {
                    watchHistory.find(watchUrl(message.optString("url")).orEmpty())
                        ?.takeUnless(WatchProgress::finished)?.let { startPlayback(it) }
                }
                "dismiss" -> {
                    watchHistory.dismiss(watchUrl(message.optString("url")).orEmpty())
                    sendHistory()
                }
                "identify-series" -> {
                    val episode = watchUrl(message.optString("url")) ?: return
                    val title = message.optJSONObject("title")?.let(LibraryTitle::fromJson) ?: return
                    watchHistory.associateSeries(episode, title)
                }
                "refresh-history" -> sendHistory()
            }
            return
        }
        if (event == "toggle-favorite" || event == "resume-detail") {
            val title = titleUrl(page) ?: return
            if (event == "resume-detail") {
                if (resumed) watchHistory.resumeForTitle(title)?.let { startPlayback(it) }
            } else {
                val item = message.optJSONObject("title")?.let(LibraryTitle::fromJson)
                    ?.takeIf { it.url == title } ?: return
                val saved = favorites.toggle(item)
                playbackPort?.postMessage(WebMessage(JSONObject().put("favorite", saved).toString()))
            }
            return
        }
        if (isSeriesPage(page)) {
            when (event) {
                "series-unavailable" -> showError()
                "play-series" -> if (resumed) {
                    val episode = WatchItem.fromPage(message.optString("url"), JSONObject())
                    if (episode != null && "/episodes/" in episode.url) {
                        startPlayback(WatchProgress(episode, 0, 0, null))
                    }
                }
            }
            return
        }
        if (event == "remote" && (isPlayerSite(page) || isWatchPage(page))) {
            playerRemote = when (message.optString("target")) {
                "controls" -> PlayerRemote.CONTROLS
                "frame" -> PlayerRemote.FRAME
                else -> PlayerRemote.PAGE
            }
            return
        }
        if (isWatchPage(page) && event == "successor") {
            val item = watchItem?.takeIf { it.url == watchUrl(page) } ?: return
            val next = watchUrl(message.optString("url"))?.takeIf { it != item.url && it.contains("/episodes/") }
            if (next != null && item.url.contains("/episodes/")) {
                watchItem = item.copy(next = NextEpisode(next, message.optString("title").trim().take(180),
                    startsSeason = message.optBoolean("nextSeason")))
            }
            playbackPort?.postMessage(WebMessage("season-lookup-complete"))
            return
        }
        if (!isPlayerSite(page)) return
        if (event == "quality-options") {
            val options = message.optJSONArray("options") ?: return
            availableQualities = (0 until options.length()).mapNotNull { videoQuality(options.optInt(it)) }.distinct().sorted()
            quality = message.optInt("selected").takeIf { it in availableQualities } ?: return
            playbackPort?.postMessage(WebMessage("quality-options-ready"))
            return
        }
        if (event == "quality") {
            quality = videoQuality(message.optInt("quality")) ?: quality
            return
        }
        val item = watchItem ?: return
        when (event) {
            "change-quality" -> {
                if (!resumed || !hasWindowFocus() || finishedEpisode) return
                val automatic = message.optBoolean("automatic")
                val target = message.optInt("quality").takeIf { it in availableQualities }
                if (!automatic && target == null) return
                if (message.optBoolean("buffering")) {
                    if (preferredQuality != null || target == null || target >= (quality ?: 0)) return
                } else {
                    preferredQuality = if (automatic) null else target
                    playbackSettings.edit().putInt("preferred_quality", preferredQuality ?: 0).apply()
                }
                if (target == null || target == quality) return
                val duration = message.optInt("duration")
                val position = message.optInt("position", -1)
                if (duration !in 1..PlaybackHistory.MAX_DURATION || position !in 0 until duration) return
                val progress = WatchProgress(item, position, duration, quality)
                watchHistory.savePosition(progress)
                startPlayback(progress, fullscreen = message.optBoolean("fullscreen"),
                    playing = message.optBoolean("playing"), requestedQuality = target)
            }
            "progress" -> {
                if (finishedEpisode) return
                val duration = message.optInt("duration")
                val position = message.optInt("position", -1)
                if (duration !in 1..PlaybackHistory.MAX_DURATION || position !in 0..duration) return
                watchHistory.remember(WatchProgress(item, position, duration, quality))
                playbackLaunch = null
                web.settings.mediaPlaybackRequiresUserGesture = true
            }
            "ended" -> if (!finishedEpisode) {
                finishedEpisode = true
                watchHistory.complete(item, quality)
            }
            "replay" -> finishedEpisode = false
            "skip" -> if (resumed && hasWindowFocus()) {
                val target = when (message.optString("direction")) {
                    "next" -> item.next
                    "previous" -> previousEpisode?.takeIf { it.first == item.url }?.second
                    else -> null
                } ?: return
                finishedEpisode = false
                startPlayback(WatchProgress(target.asWatchItem(item.poster, item.series), 0, 0, quality),
                    fullscreen = true)
            }
            "next" -> if (resumed && hasWindowFocus() && finishedEpisode) {
                val next = item.next ?: return
                finishedEpisode = false
                startPlayback(WatchProgress(next.asWatchItem(item.poster, item.series), 0, 0, quality),
                    fullscreen = true)
            }
        }
    }

    private fun sendHistory() {
        playbackPort?.postMessage(WebMessage(JSONObject().put("history", watchHistory.forHome()).toString()))
    }

    private fun openHome() {
        hideFullscreen()
        playbackReturn = null
        catalogueHistory.clear()
        if (rendererGone) {
            createWebView()
            rendererGone = false
        }
        load(HOME)
    }

    private fun showLoading() {
        generation++
        ready = false
        failed = false
        handler.removeCallbacks(timeout)
        message.setText(R.string.loading)
        retry.visibility = View.GONE
        home.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.requestFocus()
        handler.postDelayed(timeout, 35_000)
    }

    private fun showError(text: Int = R.string.load_error) {
        failed = true
        ready = false
        handler.removeCallbacks(timeout)
        status.visibility = View.VISIBLE
        message.setText(text)
        retry.visibility = View.VISIBLE
        home.visibility = View.VISIBLE
        retry.requestFocus()
    }

    private fun preparePage(view: WebView, url: String) {
        if (view !== web || failed || !isSite(url)) return
        val loadingGeneration = generation
        view.evaluateJavascript(navigationScript()) { result ->
            if (generation != loadingGeneration || failed || ready ||
                view !== web || isFinishing || !isSite(view.url.orEmpty())) return@evaluateJavascript
            val details = runCatching { JSONObject(result) }.getOrNull() ?: return@evaluateJavascript
            val page = view.url ?: url
            activeUrl = page
            if (isCatalogueSite(page)) rememberCataloguePage(page)
            ready = true
            connectPlayback(view, page, details)
            handler.removeCallbacks(timeout)
            status.visibility = View.GONE
            web.requestFocus()
            runPage("refresh(true)")
            advanceUpdater()
        }
    }

    private fun rememberCataloguePage(page: String) {
        if (isHome(page)) catalogueHistory.clear()
        if (catalogueHistory.lastOrNull()?.let { samePage(it, page) } == true) {
            catalogueHistory[catalogueHistory.lastIndex] = page
        } else {
            catalogueHistory.add(page)
            if (catalogueHistory.size > 50) catalogueHistory.removeAt(0)
        }
        if (isWatchPage(page)) playbackReturn = page
    }

    private inner class SiteClient : WebViewClient() {
        private fun screenShell(title: String, body: String): WebResourceResponse {
            val shell = """<!doctype html><html lang="fa"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1"><title>$title</title>
                </head><body>$body</body></html>"""
            return WebResourceResponse("text/html", "UTF-8", 200, "OK",
                mapOf("Cache-Control" to "no-store"), shell.byteInputStream())
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.method != "GET") return null
            val url = request.url.toString()
            if (request.isForMainFrame && isFavoritesPage(url)) {
                return screenShell("علاقه‌مندی", "")
            }
            if (request.isForMainFrame && isSeriesPage(url)) {
                // The shell fetches the catalogue as data using WebView's existing site session.
                return screenShell("Amin player",
                    """<div id="fl-series-loading" role="status">در حال بارگذاری قسمت‌ها…</div>""")
            }
            // Posters from the provider's desktop catalogue are unused on the player screen.
            val path = request.url.path.orEmpty().lowercase()
            if (!request.isForMainFrame && isPlayerSite(url) && path.startsWith("/wp-content/uploads/") &&
                path.substringAfterLast('.') in setOf("jpg", "jpeg", "png", "webp", "gif", "avif")) {
                return WebResourceResponse("text/plain", null, 204, "No Content", emptyMap(), "".byteInputStream())
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.isForMainFrame && blockUnknown(request.url.toString())

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            blockUnknown(url)

        private fun blockUnknown(url: String): Boolean {
            if (isSite(url)) return false
            Toast.makeText(this@MainActivity, R.string.unavailable_link, Toast.LENGTH_SHORT).show()
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            if (view !== web) return
            if (!isSite(url)) {
                view.stopLoading()
                showError()
                return
            }
            activeUrl = url
            closePlaybackPort()
            if (!isPlayerSite(url) && playbackLaunch?.progress?.item?.url != watchUrl(url)) playbackLaunch = null
            view.settings.mediaPlaybackRequiresUserGesture = !isPlayerSite(url) || playbackReturn == null || !resumed
            if (ready) hideFullscreen()
            showLoading()
        }

        override fun onPageCommitVisible(view: WebView, url: String) = preparePage(view, url)

        override fun onPageFinished(view: WebView, url: String) {
            preparePage(view, url)
            CookieManager.getInstance().flush()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (view === web && isSite(url)) {
                activeUrl = url
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view === web && request.isForMainFrame) showError()
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (view === web && request.isForMainFrame) showError()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view !== web) return false
            closePlaybackPort()
            playbackLaunch = null
            hideFullscreen()
            root.removeView(view)
            view.destroy()
            rendererGone = true
            showError(R.string.renderer_error)
            return true
        }
    }

    private inner class TvChromeClient : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreen != null || !ready) {
                callback.onCustomViewHidden()
                return
            }
            fullscreen = view
            fullscreenCallback = callback
            root.addView(view, FrameLayout.LayoutParams(-1, -1))
            view.isFocusableInTouchMode = true
            view.requestFocus()
            hideSystemBars()
        }

        override fun onHideCustomView() = hideFullscreen()
    }

    private fun hideFullscreen() {
        val view = fullscreen ?: return
        val callback = fullscreenCallback
        fullscreen = null
        fullscreenCallback = null
        root.removeView(view)
        callback?.onCustomViewHidden()
        if (!rendererGone) web.requestFocus()
        hideSystemBars()
    }

    private fun runPage(action: String, result: ((String) -> Unit)? = null) {
        if (rendererGone || !isSite(web.url.orEmpty())) {
            result?.invoke("null")
            return
        }
        web.evaluateJavascript("window.FarsiLandTv && window.FarsiLandTv.$action") { result?.invoke(it) }
    }

    private fun routePlayerDirection(event: KeyEvent): Boolean {
        if (!ready) return false
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            else -> return false
        }
        val vertical = direction == "up" || direction == "down"
        val nativePlayer = fullscreen != null && (isPlayerSite(activeUrl) || isWatchPage(activeUrl))
        val pageHandlesDirection = playerRemote == PlayerRemote.CONTROLS || (playerRemote == PlayerRemote.FRAME && vertical)
        if (nativePlayer && !vertical) return false
        if (!nativePlayer && !pageHandlesDirection) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (nativePlayer) hideFullscreen()
            if (!web.hasFocus()) web.requestFocus()
            runPage("playerDirection('$direction', ${event.repeatCount > 0})")
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (routePlayerDirection(event)) return true
        val media = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "toggle"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> "pause"
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "forward"
            KeyEvent.KEYCODE_MEDIA_REWIND -> "rewind"
            else -> null
        }
        if (media != null && ready) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) runPage("media('$media')")
            return true
        }
        val select = event.keyCode in SELECT_KEYS
        if (!ready || !select) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true

        // Enter arrives in the page as a trusted key event, which fullscreen and playback can require.
        val enter = KeyEvent(
            event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_ENTER,
            event.repeatCount, event.metaState, event.deviceId, 0, event.flags, event.source
        )
        if (playerRemote == PlayerRemote.CONTROLS && fullscreen == null) {
            if (!web.hasFocus()) web.requestFocus()
            web.dispatchKeyEvent(enter)
        } else {
            super.dispatchKeyEvent(enter)
        }
        if (event.action == KeyEvent.ACTION_UP && fullscreen == null) {
            runPage("editing()") {
                if (it == "true" && ready && fullscreen == null) {
                    getSystemService(InputMethodManager::class.java)
                        .showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        return true
    }

    @Deprecated("The TV remote uses the classic Back event.")
    override fun onBackPressed() {
        if (backPending) return
        if (fullscreen != null) {
            hideFullscreen()
            return
        }
        if (!ready) {
            backInHistory()
            return
        }
        backPending = true
        val pageGeneration = generation
        val fallback = Runnable {
            if (backPending) {
                backPending = false
                if (generation == pageGeneration) backInHistory()
            }
        }
        handler.postDelayed(fallback, 700)
        runPage("back()") { handled ->
            handler.removeCallbacks(fallback)
            if (!backPending) {
                return@runPage
            }
            backPending = false
            if (handled != "true" && pageGeneration == generation) backInHistory()
        }
    }

    private fun backInHistory() {
        if (isHome(activeUrl)) {
            finish()
            return
        }
        if (rendererGone) {
            openHome()
            return
        }
        if (isPlayerSite(activeUrl)) {
            runPage("media('pause')")
            playbackLaunch = null
            // Provider POSTs and redirects can replace or skip the watch page in WebView history.
            load(playbackReturn ?: catalogueHistory.lastOrNull() ?: HOME)
            return
        }
        if (catalogueHistory.lastOrNull()?.let { samePage(it, activeUrl) } == true) {
            catalogueHistory.removeAt(catalogueHistory.lastIndex)
        }
        load(catalogueHistory.lastOrNull() ?: HOME)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!rendererGone) {
            outState.putBundle("history", Bundle().also { web.saveState(it) })
        }
        outState.putString("playbackReturn", playbackReturn)
        outState.putStringArrayList("catalogueHistory", ArrayList(catalogueHistory))
    }

    override fun onPause() {
        resumed = false
        VpnStatus.remove(vpnObserver)
        playbackLaunch = null
        if (!rendererGone) {
            runPage("media('pause')")
            web.settings.mediaPlaybackRequiresUserGesture = true
            web.onPause()
        }
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        VpnStatus.observe(vpnObserver)
        sendVpnState()
        if (!rendererGone) web.onResume()
        hideSystemBars()
        updater.onResume()
        advanceUpdater()
    }

    private fun vpnState(): JSONObject = JSONObject()
        .put("label", VpnStatus.current.actionLabel(this))
        .put("status", VpnStatus.current.statusLabel(this))
        .put("stopping", VpnStatus.current.phase == VpnPhase.STOPPING)

    private fun sendVpnState() {
        if (::fallbackVpn.isInitialized) fallbackVpn.text = VpnStatus.current.actionLabel(this)
        if (resumed && ready && isHome(activeUrl)) {
            playbackPort?.postMessage(WebMessage(JSONObject().put("vpn", vpnState()).toString()))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            advanceUpdater()
        }
    }

    private fun advanceUpdater() {
        if (resumed && fullscreen == null && hasWindowFocus()) updater.advance(atHome = isHome(activeUrl))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        closePlaybackPort()
        updater.close()
        hideFullscreen()
        if (!rendererGone) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val HOME = "https://farsiland.com/home"
        private val THEMES = listOf("midnight", "saffron", "turquoise", "pomegranate", "garden",
            "galaxy", "forest", "nights", "ice", "dragon", "castle", "orbit", "theatre", "rose", "highlands")
        private val SELECT_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

        private fun siteHost(url: String) = PlaybackSites.host(url)
        private fun isCatalogueSite(url: String) = PlaybackSites.isCatalogue(url)
        private fun isPlayerSite(url: String) = PlaybackSites.isPlayer(url)
        private fun isSite(url: String) = isCatalogueSite(url) || isPlayerSite(url)
        private fun isSeriesPage(url: String) = isCatalogueSite(url) &&
            Uri.parse(url).pathSegments.let { it.size == 2 && it[0] == "tvshows" }
        private fun isFavoritesPage(url: String) = isCatalogueSite(url) &&
            Uri.parse(url).path.orEmpty().trimEnd('/') == "/amin-player/favorites"
        private fun samePage(first: String, second: String): Boolean =
            siteHost(first) == siteHost(second) &&
                Uri.parse(first).path.orEmpty().trimEnd('/') == Uri.parse(second).path.orEmpty().trimEnd('/') &&
                Uri.parse(first).encodedQuery.orEmpty() == Uri.parse(second).encodedQuery.orEmpty()

        private fun isWatchPage(url: String): Boolean =
            isCatalogueSite(url) && Uri.parse(url).path.orEmpty().let {
                it.startsWith("/episodes/") || it.startsWith("/movies/")
            }

        private fun isHome(url: String): Boolean {
            val uri = Uri.parse(url)
            return isCatalogueSite(url) && uri.path.orEmpty().trimEnd('/') in setOf("", "/home") &&
                uri.query.isNullOrEmpty()
        }
    }
}
