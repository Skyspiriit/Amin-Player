# Amin Player v1.16 diagnostics

This investigation used the shipped APK. The application, signing configuration, updater, and existing release assets were left as published.

## Release and source verification

| Item | Verified value |
| --- | --- |
| Release | [v1.16](https://github.com/Skyspiriit/Amin-Player/releases/tag/v1.16) |
| Version and package | `versionName=1.16`, `versionCode=17`, `com.enigma.farsilandtv` |
| Variant | Gradle `debug`, debuggable APK, min SDK 23, target and compile SDK 35 |
| Release APK SHA-256 | `b9924d1dd0eafb2e2b87c12c6da24b3f546f7bd6622f508d750bb2d3aa0ed6ac` |
| Release source ZIP SHA-256 | `f3007626a6baa68e3159a4d641a1d8b56a9e31b458920d558e65a80c8e55b250` |
| APK signer certificate SHA-256 | `9b8b1f2743af69b8e0d4ed81e226987e25516a635e101ddff02aa90f53ed349c` |
| Published source commit | [`5cb4a96717431a2eb977a1fcfcebb04950ace945`](https://github.com/Skyspiriit/Amin-Player/commit/5cb4a96717431a2eb977a1fcfcebb04950ace945) |
| Exact source tree | `33e047773a6f53a2d92468065209e177e0f44c5b`, 22 files |

The original v1.16 tag points to commit `1c064941180087b0946c6bc334ea58d95690fc92`, whose tree contained only a README. The release's named source asset supplied the project files. All 22 imported files matched the ZIP and the retained release build workspace byte for byte. The retained `app/build/outputs/apk/debug/app-debug.apk` matched the published APK. The packaged WebView assets also matched the imported source directly:

| Packaged asset | SHA-256 |
| --- | --- |
| `assets/tv-navigation.js` | `7583a14eafd3704911cec71087976978376410163cde00beb70cd216d079d2ad` |
| `assets/tv.css` | `b47ab0c8e8c9567f5c614acaec4d395b33da6aaaae0874b55433c20b766be609` |

[Build instructions](../../README.md#build-from-source) and [version configuration](../../app/build.gradle.kts#L8) describe Gradle Wrapper 8.13, JDK 17, and SDK 35. An independent rebuild and a compiler-level Kotlin comparison were outside this diagnostic run. Byte-for-byte APK reproducibility is unverified and would require the original signing material and matching build environment. The signing key, credentials, and local build settings are excluded. On Unix, run the archived wrapper as `sh ./gradlew` if its executable bit is absent.

## Rendering architecture

| Screen or concern | Source and execution |
| --- | --- |
| Native application shell | [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml) registers `MainActivity` as the launcher and Leanback activity. [`MainActivity.onCreate` and `createWebView`](../../app/src/main/java/com/enigma/farsilandtv/MainActivity.kt#L86) create the native `FrameLayout`, WebView, and loading/error controls. Native code also routes remote events and owns playback history, Favorites, and the updater. The release source has no Fragment-based detail screen. |
| Injection | [`navigationScript`](../../app/src/main/java/com/enigma/farsilandtv/MainActivity.kt#L78) reads the JavaScript and CSS from the APK assets. [`preparePage`](../../app/src/main/java/com/enigma/farsilandtv/MainActivity.kt#L440) evaluates them at WebView commit or finish, reads `watchInfo()`, and opens an origin-restricted message port for app state. |
| Home | The WebView loads FarsiLand `/home`. [`installFarsiLandTv` and `addToolbar`](../../app/src/main/assets/tv-navigation.js#L235) add TV navigation and styling to the site's catalogue. [`mountContinueWatching`](../../app/src/main/assets/tv-navigation.js#L1202) renders local records supplied by [`PlaybackHistory.forHome`](../../app/src/main/java/com/enigma/farsilandtv/PlaybackHistory.kt#L101). The catalogue posters remain website DOM. |
| Movie | The main frame loads the original FarsiLand `/movies/{slug}/` document. [`mountMovie`, `detailHeader`, and `tidyDetails`](../../app/src/main/assets/tv-navigation.js#L636) add the app header and hide selected website blocks. With a recognized provider control, the app retains the original Play element for the handoff and hides its original visual wrapper. Other sections can remain in the page. [Movie CSS](../../app/src/main/assets/tv.css#L403) |
| Series | For a main-frame GET to canonical `/tvshows/{slug}/`, [`SiteClient.shouldInterceptRequest`](../../app/src/main/java/com/enigma/farsilandtv/MainActivity.kt#L480) supplies a small HTML shell with a scale-1 viewport. [`loadSeries`](../../app/src/main/assets/tv-navigation.js#L781) fetches the provider document as data and parses it in a detached template. [`mountSeries`](../../app/src/main/assets/tv-navigation.js#L808) builds the visible overview and separate picker. [`tv.css`](../../app/src/main/assets/tv.css#L475) controls columns, poster size, synopsis clamping, and scrolling. |
| Metadata and episodes | [`detailMetadata`](../../app/src/main/assets/tv-navigation.js#L611) reads title, poster, and date metadata. [`seriesSynopsis`](../../app/src/main/assets/tv-navigation.js#L800) reads series synopsis paragraphs and prefers Persian. [`readSeasons`](../../app/src/main/assets/tv-navigation.js#L566) reads and sorts episode links from `#seasons`. [`episodeSeries` and `watchInfo`](../../app/src/main/assets/tv-navigation.js#L509) keep the episode’s series identity and next link. |
| Video URL and player | [`playerLink`, `checkPlayerLink` and `continuePlayback`](../../app/src/main/assets/tv-navigation.js#L681) locate the existing FarsiLand watch link or form, validate its `farsiland.buzz` destination, and activate it. The provider POST and subsequent provider page resolve the stream. [`markQuality`](../../app/src/main/assets/tv-navigation.js#L996) detects provider quality controls; [`mountPlayer`](../../app/src/main/assets/tv-navigation.js#L1596) adds TV controls around the provider's visible video. |
| Remote and focus | [`dispatchKeyEvent` and `routePlayerDirection`](../../app/src/main/java/com/enigma/farsilandtv/MainActivity.kt#L598) route media and D-pad events and translate OK to Enter. [`candidates`, `allowed`, `chooseDirection`, and `select`](../../app/src/main/assets/tv-navigation.js#L281) restrict focus, choose targets by geometry, and set `data-fl-focus`. [Focus CSS](../../app/src/main/assets/tv.css#L129) draws the gold highlight. |

## Series navigation trace

1. **Open Amin Player.** `MainActivity.onCreate` restores a valid existing WebView history entry or calls `openHome()`. The home catalogue comes from FarsiLand. The app supplies the toolbar and Continue Watching data.
2. **Select a series card.** JavaScript `activate()` triggers the selected anchor. `prepareSeriesVisit()` clears old series focus on a fresh visit or records the current episode when following All Episodes from an episode page. [`activate`](../../app/src/main/assets/tv-navigation.js#L1685) · [`prepareSeriesVisit`](../../app/src/main/assets/tv-navigation.js#L966)
3. **Enter series details.** The canonical GET receives the app shell. Native `connectPlayback()` sends the series flag, saved episode, and Favorite state. `loadSeries()` fetches and parses the catalogue, and `mountSeries()` inserts the overview. A failed series fetch sends `series-unavailable` to the native Retry/Home screen. [`connectPlayback`](../../app/src/main/java/com/enigma/farsilandtv/MainActivity.kt#L223) · [`receivePlayback`](../../app/src/main/java/com/enigma/farsilandtv/MainActivity.kt#L281)
4. **Press همه قسمت‌ها.** `openPicker()` switches panels inside the same document. `show()` uses the ordered catalogue data, 12 entries per page, with RTL display and saved episode focus. [Picker and pagination](../../app/src/main/assets/tv-navigation.js#L896)
5. **Select an episode.** The picker opens its `/episodes/{slug}/` URL. This route loads the provider’s original watch document. `mountEpisodeMenu()` adds navigation shortcuts and leaves the provider Play handoff in place. [`mountEpisodeMenu`](../../app/src/main/assets/tv-navigation.js#L946)
6. **Return to the series.** `onBackPressed()` lets JavaScript handle overlays and the picker. Native `backInHistory()` otherwise reloads the previous catalogue route. The stored episode link restores its season, page, and focus. A subsequent Back closes the picker to the overview. [Native Back](../../app/src/main/java/com/enigma/farsilandtv/MainActivity.kt#L660) · [JavaScript Back](../../app/src/main/assets/tv-navigation.js#L1717)
7. **Press Play or Resume.** `detailHeader()` sends `resume-detail` when a saved item qualifies, or the series action sends `play-series` with its first episode. Native `startPlayback()` loads the real watch page and sets the playback handoff. `continuePlayback()` activates the provider flow and `applyResumePosition()` seeks once duration is available. [Playback launch](../../app/src/main/java/com/enigma/farsilandtv/MainActivity.kt#L206) · [Resume seek](../../app/src/main/assets/tv-navigation.js#L1366)

The distinction is route-based. Series detail is an app-built document. Home, movie, individual episode, and provider player routes preserve different amounts of the provider page. Android documents that `shouldInterceptRequest` sees the original URL of a redirect, which gives a concrete alternative-path hypothesis to verify on hardware if the reported entry point was noncanonical. [Android WebViewClient reference](https://developer.android.com/reference/android/webkit/WebViewClient#shouldInterceptRequest(android.webkit.WebView,android.webkit.WebResourceRequest))

## Capture evidence

Every capture is an unedited screenshot of the released APK running on the Android TV 14 emulator at 320 dpi. The first nine use 1920×1080. The final two use the same emulator with a temporary 1280×720 output setting. [Display measurements and limits](FINDINGS.md#android-tv-comparison)

| Capture | Visible evidence |
| --- | --- |
| [01 Home](screenshots/01-tv1080-home.png) | TV toolbar, Continue Watching, and catalogue cards after images loaded. |
| [02 Series opening](screenshots/02-tv1080-series-opening.png) | Persian loading screen while the catalogue is fetched. |
| [03 Settled series](screenshots/03-tv1080-series-ready.png) | Complete visible overview with poster, synopsis, and actions after loading. |
| [04 Series with focus](screenshots/04-tv1080-series-focus.png) | Gold D-pad outline on همه قسمت‌ها. |
| [05 Episode picker](screenshots/05-tv1080-episode-picker.png) | Picker after pressing OK on همه قسمت‌ها. |
| [06 Movie](screenshots/06-tv1080-movie.png) | App header and visible FarsiLand sharing and comment controls. |
| [07 Episode watch](screenshots/07-tv1080-episode-watch.png) | Website video cover and provider Play control. |
| [08 Lower episode area](screenshots/08-tv1080-episode-lower.png) | Duplicate episode heading, ratings, and social sharing. |
| [09 Series with cache disabled](screenshots/09-tv1080-series-cache-disabled.png) | Same overview after navigating with Chromium’s cache disabled. |
| [10 Series at 720p](screenshots/10-tv720-series-ready.png) · [11 With focus](screenshots/11-tv720-series-focus.png) | Comparable overview and D-pad focus on the smaller viewport. |
