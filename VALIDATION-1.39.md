# Amin Player 1.39 — preparation and validation

Baseline: stable v1.37, commit `b41eb253c870a25b765ec549a345c593217cee63`.
Prepared version: **1.39 / versionCode 40**, package `com.enigma.farsilandtv`.
Version 1.38 / code 39 is reserved by separate, unreleased local VPN work; that work was left untouched.
This change does not publish a production release or automatically install on a family TV.

## Homepage date investigation (25 November 2026)

The requested date means the addition of a **playable link**, not publication of a catalogue page.

| Source inspected | Finding | Suitable as a playable-link addition timestamp? |
| --- | --- | --- |
| [Home](https://farsiland.com/home), [episodes listing](https://farsiland.com/episodes-15/), movie listings and sitemap | Lists and page metadata, but no verifiable link-addition ledger or time boundaries. Home/listing modification fields can be years old. | No |
| [Roshan EP12](https://farsiland.com/episodes/roshan-ep12/) HTML and VideoObject | `uploadDate` matches the date displayed as the episode's Release Date. | No |
| [Movie Saghf](https://farsiland.com/movies/saghf/) | The visible date is a film release field. Its year may label that movie, but cannot label a recommendation as new. | No |
| [Public episode API](https://farsiland.com/wp-json/wp/v2/episodes?per_page=2), movies API, post-type/schema and route index | WordPress record publication/edit dates; no verified timestamp for attaching a playable link. Some scheduled publication dates are later than edit dates. | No |
| Public DooPlayer metadata endpoint for inspected records | No usable timestamp returned. | No |

[WordPress documents `date` as post publication and `modified` as post modification](https://developer.wordpress.org/rest-api/reference/posts/#schema).
Neither proves when a playable link was attached. Listing order alone cannot establish the requested 48-hour/seven-day windows.

**Both date groups are therefore disabled and hidden.** The three-group implementation accepts only an explicitly verified timestamp source; its production adapter returns unavailable. There are no fake dates, first-visit “new” labels, new tracking stores or extra homepage scraping requests. If a suitable source becomes available, the grouping logic already enforces disjoint 48-hour/seven-day windows, newest-first sorting and canonical-link deduplication.

The existing rotating recommendation cards keep their style, timing, focus handling and visit behavior under **پیشنهاد برای تماشا**. Continue Watching's rendering, content, ordering and resume behavior are unchanged.

## Playback overlay

- Selected catalogue metadata travels through the existing native, origin-scoped player bridge and playback history. No player redirect, form, POST body, cookie/session logic or media URL is replaced.
- Movies use their selected title and only an available, unambiguous visible release year. Episodes use the actual series link and the row matching the selected episode URL; missing/conflicting season or episode facts are omitted. A following episode never inherits the preceding episode's numbers.
- Revealing existing playback controls reveals the overlay. White bold title, warm gold season/episode, dark translucent card, mixed Persian/English wrapping and a brief fade. It disappears after five seconds and can be shown again.
- The card has no interactive/focusable elements and ignores pointer events. Its timer never moves focus. Back retains its existing behavior; quality/other modal dialogs hide the title card.
- Regular and Bold Vazirmatn are bundled (101,704 bytes together), unmodified, with the SIL Open Font License and source/checksums in `app/src/main/assets/fonts/`. No external font service or new library is used. Only fixed HTTPS font paths on existing trusted hosts are intercepted.
- Startup animation/audio, VPN engines/settings, updater, quality policy, next episodes, favourites and Continue Watching are otherwise unchanged.

## Automated and device validation

- JavaScript syntax check: passed.
- Node tests: **40 passed** (seven new freshness/overlay checks plus existing regressions).
- Android JVM unit tests: **31 passed** in debug and release variants.
- Android debug and ARMv7 release builds: passed.
- Android lint, debug and release: **zero errors**; release report contains 35 warnings and one hint, including existing API/deprecation/style findings. No lint suppression added for this feature.
- Android TV 14, x86, 1920×1080 disposable emulator: **three new deterministic device tests passed**. They cover selected-row extraction, movie release year, metadata/history round-trip, legacy history, no inherited episode numbers, real native message bridge on trusted origins, long mixed-script title layout, white/gold contrast on a bright fixture, no focusable title elements, no control overlap, five-second hiding, repeat reveal, and Back exiting fullscreen.
- One preliminary fixture run used the wrong WebView test-loading lifecycle; the fixture now supplies a local response to a real HTTPS navigation and delegates to the production client. A subsequent emulator run stopped unexpectedly after screenshot capture; the final complete rerun passed all three tests. These checks do not establish actual streamed-video playback.
- Additional live playback test: **passed** on the emulator. Roshan season 1 episode 12 followed the actual catalogue-to-`farsiland.buzz` handoff, played at 720p (duration 2,765 seconds), and advanced continuously for at least 15 seconds. Remote Up showed the exact selected title and Persian season/episode labels; bundled font readiness and media Pause/Play passed. That stream exposed only one quality, so its quality dialog was not exercised. Live FarsiPlex, a live movie/year, seeking, next-season transitions and resume were not retested by this live check; existing regression tests and deterministic origin fixtures cover the changed code paths, not every provider behavior.
- The physical Xiaomi was reachable and reported **v1.37 / code 38** during a read-only check. Installation and playback testing require the owner's explicit approval; no approval is inferred from availability.
- Two stale baseline instrumentation fixtures referenced the removed single-profile secret-store API. Their setup/cleanup now uses the existing encrypted multi-profile API. VPN runtime code is unchanged.

## Signed APK

- Asset convention: `Amin-player-40.apk`; ARMv7 only.
- Original package and signing identity preserved. Certificate SHA-256:
  `9b8b1f2743af69b8e0d4ed81e226987e25516a635e101ddff02aa90f53ed349c`.
- Signature verified against the actual previous stable APK. APK signature schemes v1/v2/v3 verified.
- Prepared APK is approximately **17.33 MiB**, below the existing **32 MiB** updater limit. The previous published APK is 20,754,023 bytes; this build uses release packaging, so the difference must not be attributed to this UI change.
- Final artifact hash, exact byte size, device results and screenshots accompany the local APK. No signing key, private VPN file, credentials or raw browsing log is included in the source archive.

## Reproduce

Use the configured JDK 17 and Android SDK, plus the original signing identity for upgrade tests.

```powershell
node --check app/src/main/assets/tv-navigation.js
node --test tests/*.test.cjs
./gradlew.bat -PaminTargetAbi=x86 :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
./gradlew.bat -PaminTargetAbi=armeabi-v7a :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

Target only an explicitly chosen disposable emulator when running deterministic instrumentation fixtures, which may change that emulator's test settings. Run `TitleOverlayDeviceTest` for the three local tests. `TitleOverlayLiveDeviceTest` skips unless explicitly passed `-e aminLivePlayback true`; it accesses a real episode and records title/host/control evidence without signed media URLs. Do not run VPN fixtures on a configured family box.
