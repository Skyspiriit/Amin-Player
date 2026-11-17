# Amin player

This personal Android TV app opens FarsiLand with large Persian controls and a gold highlight that moves with the remote. The Android TV launcher tile is named **Amin player** and has a teal play symbol.

## Install on a Xiaomi TV Box

1. Copy `Amin-player-17.apk` from the [Amin player release](https://github.com/Skyspiriit/Amin-Player/releases/tag/v1.16) to a USB drive and insert it into the TV box.
2. Open the APK from a file manager on the box. If Android prompts you, allow this file manager to install apps, then choose **Install**. On some Xiaomi models the permission is under **Settings → Apps → Security & restrictions → Install unknown apps**. The exact menu varies by model. [Xiaomi installation help](https://www.mi.com/global/support/faq/details/KA-06364/)
3. Open **Amin player** from **Apps** on the TV home screen. You can turn the file manager's install permission off once installation is complete.

### Install from a computer on the same Wi-Fi

1. On the box, open **Settings → System → About** or **Settings → Device Preferences → About**. Select **Android TV OS build** or **Build** seven times with the remote. Some older Mi Boxes use the product model entry. [Xiaomi developer options](https://www.mi.com/my/support/article/KA-07022/) · [Older Mi Box settings](https://www.mi.com/global/support/article/KA-06513/)
2. Open **Developer options** and enable **Wireless debugging** if available. Choose **Pair device with pairing code**. Use the address and code shown on that screen for `adb pair IP:PAIRING_PORT`. If manual connection is needed after pairing, run `adb connect IP:DEBUGGING_PORT` with the address on the main Wireless debugging screen. [Android wireless debugging](https://developer.android.com/tools/adb?hl=en#connect-to-a-device-over-wi-fi)
3. On older boxes with **ADB debugging**, look under **Developer options** or **Account & security**. If the box accepts ADB over Wi-Fi, use its local IP from **Settings → Network & Internet** and approve the connection prompt on the TV. If the model only makes ADB available over USB, use the USB-drive installation steps above. [Xiaomi ADB help](https://www.mi.com/global/support/article/KA-06513/)
4. Once the device is connected, install or update the app:

```text
adb install -r Amin-player-17.apk
```

## Watch with the remote

1. Use **Left/Right** to choose a poster and **Up/Down** to change rows. Press **OK** to open the highlighted item.
2. A series opens to its poster, synopsis and playback actions. Choose **پخش** (Play) to start the first episode or **ادامهٔ پخش** (Resume) to continue watching. Use **همه قسمت‌ها** (All Episodes) to open the episode picker. The season and episode numbers increase from right to left. **Back** closes the picker and returns to the series overview. If a previous episode exists, **قسمت قبلی** opens that episode.
3. Playback starts automatically. Select **کیفیت** (Quality) in the player to choose **خودکار** (Auto) or an available resolution. Auto chooses a starting quality based on the connection and lowers it when playback keeps buffering. A resolution change briefly reloads the video and keeps the playback position. **Up** from the player buttons opens the timeline, even when the controls have faded. **Down** returns to the player buttons. Outside fullscreen, another **Up** from the timeline reaches the top menu. With the timeline selected, use **Left/Right** to seek or hold either arrow to move faster.
4. Press **Back** to exit fullscreen and again to return to the episode page. Above **پخش** you can choose **همهٔ قسمت‌ها** (All episodes), **قسمت قبلی** (Previous episode), or **قسمت بعدی** (Next episode). Back restores the episode you selected when you return to the series list. Use **خانه** in the top menu to go home.
5. Select the **جستجو** (Search) bar to open the built-in Persian keyboard. Use the arrows and **OK** to choose letters. Choose **English** to change the keyboard and **جستجو** below the keys to submit.
6. If a page fails to load, choose **تلاش دوباره** to retry.
7. **ادامهٔ تماشا** (Continue Watching) shows up to five recent movies and series. Open a card and choose **▶ پخش** (Play) or **✕ حذف از ادامه پخش** (Remove). Removed titles disappear from the row and can return when you watch them again. At the end of an episode, the next episode starts after five seconds. If a new season is available, its first episode starts after the same countdown. Press **OK** to start immediately or choose **بعداً** or **Back** to cancel.
8. Select the heart on a movie or series detail page to add or remove it from Favorites. Open **علاقه‌مندی** from the top menu to see saved titles. Favorites and playback progress are stored separately on the TV.

> با دکمه‌های جهت‌دار روی فیلم، سریال یا قسمت بروید و **OK** را بزنید. برای تماشا **پخش** را انتخاب کنید. با دکمهٔ **بازگشت** ابتدا از تمام‌صفحه خارج می‌شوید و بعد به صفحهٔ قسمت برمی‌گردید.

## Update

If you are installing for the first time or updating from a version older than 1.3, install the latest APK from the TV file manager or with the command above. It uses the original package and signing key, so it can update earlier versions in place and keep their data and cookies.

From 1.3 onward, Amin player checks GitHub when opened. When it finds a newer signed release, it downloads it and shows **نسخهٔ تازه آماده است** on the homepage. Choose **نصب به‌روزرسانی** (Install update) or **بعداً** (Later). If the app needs installation permission, allow **Amin player** in the profile where it is running. On the main TV profile, choose the entry without a briefcase badge. Press **Back** to return and choose **Update** or **Install** on Android’s confirmation screen. Version 1.11 can resume a verified download after a restart or when the website or GitHub is temporarily unavailable. If the older updater is stuck, install 1.11 once over the existing app to keep its data. [GitHub releases](https://github.com/Skyspiriit/Amin-Player/releases) · [Android installer permissions](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())

For future releases, increment both `versionCode` and `versionName` in `app/build.gradle.kts`, build with the original signing key, and publish a stable GitHub release. Name the attached APK `Amin-player-<versionCode>.apk`. The app checks the APK’s version, package name, signer and GitHub’s SHA-256 checksum before offering it. Version 1.16 uses `versionCode 17`, so its asset is `Amin-player-17.apk`. [GitHub release management](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository) · [Android app signing](https://developer.android.com/studio/publish/app-signing)

## Build from source

Open this project directory in Android Studio with support for Android Gradle Plugin 8.13. Use JDK 17, Android SDK Platform 35 and Android SDK Build-Tools 35.0.0. The project includes Gradle Wrapper 8.13. From the project directory, use the command below on Windows, or `./gradlew assembleDebug` on macOS and Linux. [Android Gradle Plugin compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes)

```powershell
.\gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Keep the same debug keystore for future test updates and increment `versionCode` in `app/build.gradle.kts` when making a new version. The source archive excludes the build cache and signing key.

## Implementation and limits

- The Kotlin activity hosts FarsiLand and its observed playback site, `farsiland.buzz`. It keeps the site's own catalogue, search form, sign-in and playback handoff. The app uses the player controls and the video already present on the page. No public API or direct stream URL is required. [FarsiLand home](https://farsiland.com/home)
- Directional focus uses the position of visible page controls, with automatic scrolling through poster rows and focus restoration when navigating Back. The activity handles loading, reload, native fullscreen and the player’s return path.
- The app uses its own WebView cookies. If an account is needed, sign in within the app and use the site's **Remember Me** option. Session expiry is controlled by the website. The tested public episode streamed while signed out. Some account providers may require a separate browser.
- Continue Watching, the app timeline, and next-episode playback use the main player. If a video is in a cross-origin frame, OK passes remote focus into the frame. Its controls depend on that provider's keyboard support. Up, Down, or Back returns focus to the app. Use the site's alternate player link when offered if the default player fails.
- The homepage and playback handoff depend on FarsiLand’s current layout and domains. Catalogue titles may use the site's romanized spelling. Codec support, site availability and the TV keyboard's available languages depend on the box and its installed WebView.

## Verification

- **Live website in the browser.** Inspected the homepage, Taxi series and episode pages, a movie page, search results, and the sign-in dialog. The public Taxi episode played at 720p and the site player entered fullscreen. [Taxi series page](https://farsiland.com/tvshows/taxi/)
- **Android TV 14 emulator.** Installed and exercised version 1.0. D-pad events scrolled the live homepage, opened Taxi, selected episode 1, chose 720p, started playback, and entered and exited fullscreen. Back returned to the same episode and restored the selection in the series list. Selecting episode 2 and returning to the homepage also passed. Additional emulator checks covered seeking, the TV keyboard opening, a search for Taxi, and dismissing the sign-in dialog.
- **Version 1.2 focus fix.** Reproduced the hidden highlight on the Xiaomi box. Verified on the Android TV emulator that the border follows the whole card when moving between posters, stays visible when the row scrolls, and returns to the correct series after opening it and pressing Back.
- **Version 1.3 GitHub updates.** On the Android TV 14 emulator, a lower-version test build downloaded the published APK from GitHub, verified its SHA-256 checksum, and displayed the update prompt. After using the remote to allow Amin player as an install source and confirm Android’s update dialog, the system installed version 1.3.
- **Version 1.4 Persian search.** On the Android TV 14 emulator, typed **تاکسی** with D-pad events, verified space and delete, switched to English and back with the query intact, and opened six results on FarsiLand.
- **Version 1.5 series picker.** On the Android TV 14 emulator, opened **عشق ابدی** with season 3 and episodes 100 through 89 visible on the first screen. Tested switching seasons, opening the next episode directly, paging to older episodes, and using Back to restore the exact episode and page. A single-season series displayed every episode on one screen.
- **Version 1.8 playback.** On the Android TV 14 emulator, used the remote timeline to seek in both directions in **تاکسی**, stopped the app, and resumed at the saved time in 720p. Confirmed the next episode loaded a distinct video at 720p. In **عشق ابدی**, finished season 1 in fullscreen and confirmed season 2 episode 1 opened in fullscreen at 720p. Verified the home page redirect notice was hidden.
- **Version 1.9 remote navigation.** On the Android TV 14 emulator during live **تاکسی** playback, verified that the first Up or Down press after the controls faded moved focus. Also verified navigation to the top menu, seeking, pause and resume with OK, focus recovery in fullscreen, and movement from a test embedded video to the app menu.
- **Simulated outage.** Disabled network access for the emulator’s WebView and confirmed that it showed the Persian error screen. After restoring access, remote OK on **تلاش دوباره** reopened the live homepage.
- **Build checks.** The bundled Gradle Wrapper built the APK. APK signing verification passed, and Android lint reported no errors.
- **Remaining hardware check.** Test installation, the physical Bluetooth remote, and playback on the actual Xiaomi box. Account sign-in and cross-origin player controls were outside the successful playback test.
