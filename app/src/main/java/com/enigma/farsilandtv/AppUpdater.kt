package com.enigma.farsilandtv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

class UpdateFileProvider : FileProvider()

internal class AppUpdater(private val activity: Activity, private val onStateChanged: () -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private val installState = activity.getSharedPreferences("app_updates", Activity.MODE_PRIVATE)
    private var state: State = State.Idle
    private var dialog: AlertDialog? = null
    private var nextCheckAt = 0L
    private var lastNotice = 0
    private var closed = false

    private sealed interface State {
        data object Idle : State
        data object Checking : State
        data class Ready(val update: VerifiedUpdate, val pending: Boolean) : State
        data class InSettings(val update: VerifiedUpdate) : State
        data object Verifying : State
        data class ReadyToInstall(val update: VerifiedUpdate) : State
        data object InstallerOpen : State
        data object Deferred : State
        data class Notice(val message: Int) : State
    }

    fun onResume() {
        val previous = state
        state = when (previous) {
            is State.InSettings -> State.Ready(previous.update, pending = true)
            State.InstallerOpen -> State.Deferred
            else -> previous
        }
        check()
    }

    private fun check() {
        val now = SystemClock.elapsedRealtime()
        if (closed || state != State.Idle || now < nextCheckAt) return
        nextCheckAt = now + 5 * 60 * 1000
        state = State.Checking
        worker.execute {
            val result = runCatching { findUpdate() }.getOrElse { error ->
                Log.w(LOG_TAG, "Update check failed (${error.javaClass.simpleName})")
                State.Notice(if (error is InvalidUpdate) R.string.update_invalid else R.string.update_download_error)
            }
            deliver(result)
        }
    }

    private fun deliver(result: State) {
        activity.runOnUiThread {
            if (closed || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            state = result
            onStateChanged()
        }
    }

    fun advance(atHome: Boolean) {
        if (closed || activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) return
        when (val current = state) {
            is State.Ready -> if (current.pending || atHome) {
                when {
                    current.pending && canInstall() -> verifyBeforeInstall(current.update)
                    current.pending -> showOffer(current.update, permissionRequired = true)
                    else -> showOffer(current.update, permissionRequired = false)
                }
            }
            is State.ReadyToInstall -> if (canInstall()) {
                launchInstaller(current.update)
            } else {
                state = State.Ready(current.update, pending = true)
                showOffer(current.update, permissionRequired = true)
            }
            is State.Notice -> if (atHome || installState.contains(PENDING_APK)) {
                state = State.Idle
                if (current.message == R.string.update_current) clearPendingInstall()
                if (lastNotice != current.message) {
                    lastNotice = current.message
                    Toast.makeText(activity, current.message, Toast.LENGTH_LONG).show()
                }
            }
            else -> Unit
        }
    }

    private fun showOffer(update: VerifiedUpdate, permissionRequired: Boolean) {
        if (dialog?.isShowing == true) return
        dialog = AlertDialog.Builder(activity)
            .setTitle(if (permissionRequired) R.string.update_permission_title else R.string.update_ready)
            .setMessage(if (canInstall()) R.string.update_instructions else R.string.update_permission_instructions)
            .setPositiveButton(if (permissionRequired) R.string.update_open_settings else R.string.update_action) { _, _ ->
                selectUpdate(update)
            }
            .setNegativeButton(R.string.update_later) { _, _ -> deferUpdate() }
            .setOnCancelListener { deferUpdate() }
            .show()
    }

    fun close() {
        closed = true
        dialog?.dismiss()
        worker.shutdownNow()
    }

    private fun canInstall() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        activity.packageManager.canRequestPackageInstalls()

    private fun selectUpdate(update: VerifiedUpdate) {
        if (state !is State.Ready) return
        installState.edit().putString(PENDING_APK, update.apk.name)
            .putLong(PENDING_SIZE, update.spec.size).putString(PENDING_HASH, update.spec.sha256).apply()
        if (canInstall()) {
            verifyBeforeInstall(update)
        } else {
            state = State.InSettings(update)
            launchActivity {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", activity.packageName, null))
            }
        }
    }

    private fun verifyBeforeInstall(update: VerifiedUpdate) {
        state = State.Verifying
        worker.execute {
            val result = runCatching {
                val installed = installedPackage()
                when {
                    update.spec.version <= versionOf(installed) -> State.Notice(R.string.update_current)
                    isValidApk(update.apk, installed, update.spec) -> State.ReadyToInstall(update)
                    else -> findUpdate()
                }
            }.getOrElse { error ->
                Log.w(LOG_TAG, "Pending update could not be verified (${error.javaClass.simpleName})")
                State.Notice(if (error is InvalidUpdate) R.string.update_invalid else R.string.update_download_error)
            }
            deliver(result)
        }
    }

    private fun launchInstaller(update: VerifiedUpdate) {
        if (!update.apk.isFile || !update.apk.canRead() || update.apk.length() != update.spec.size) {
            state = State.Notice(R.string.update_download_error)
            onStateChanged()
            return
        }
        state = State.InstallerOpen
        val launched = launchActivity {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", update.apk)
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("Amin player update", uri)
            }
        }
        if (launched) clearPendingInstall()
    }

    private fun launchActivity(intent: () -> Intent): Boolean {
        try {
            activity.startActivity(intent())
            return true
        } catch (_: ActivityNotFoundException) {
            showInstallError()
        } catch (_: SecurityException) {
            showInstallError()
        } catch (_: IllegalArgumentException) {
            showInstallError()
        }
        return false
    }

    private fun deferUpdate() {
        clearPendingInstall()
        state = State.Deferred
    }

    private fun clearPendingInstall() {
        installState.edit().remove(PENDING_APK).remove(PENDING_SIZE).remove(PENDING_HASH).apply()
    }

    private fun showInstallError() {
        state = State.Notice(R.string.update_error)
        onStateChanged()
    }

    private data class ApkSpec(val version: Long, val size: Long, val sha256: String) {
        val filename: String get() = "Amin-player-$version.apk"
    }

    private data class ReleaseAsset(val spec: ApkSpec, val url: URL)
    private data class VerifiedUpdate(val spec: ApkSpec, val apk: File)
    private class InvalidUpdate : IOException()

    private fun latestAsset(): ReleaseAsset {
        val release = fetch(URL("https://api.github.com/repos/$REPOSITORY/releases/latest")) { input ->
            val output = ByteArrayOutputStream()
            copyBounded(input, output, MAX_METADATA_BYTES)
            JSONObject(output.toString(Charsets.UTF_8.name()))
        }
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) throw InvalidUpdate()
        val assets = release.optJSONArray("assets") ?: throw InvalidUpdate()
        val tag = release.optString("tag_name")
        if (!TAG.matches(tag)) throw InvalidUpdate()
        var latest: ReleaseAsset? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val version = APK_NAME.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val size = asset.optLong("size")
            val digest = asset.optString("digest")
            val expectedUrl = "https://github.com/$REPOSITORY/releases/download/$tag/$name"
            if (version <= 0 || size !in 1..MAX_APK_BYTES || !SHA256.matches(digest) ||
                !asset.optString("browser_download_url").equals(expectedUrl, ignoreCase = true)) continue
            if (latest == null || version > latest.spec.version) {
                val spec = ApkSpec(version, size, digest.substringAfter(':').lowercase(Locale.ROOT))
                latest = ReleaseAsset(spec, URL(expectedUrl))
            }
        }
        return latest ?: throw InvalidUpdate()
    }

    @Suppress("DEPRECATION")
    private fun signingFlags() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private fun installedPackage() = activity.packageManager.getPackageInfo(activity.packageName, signingFlags())

    private fun findUpdate(): State {
        val installed = installedPackage()
        val installedVersion = versionOf(installed)
        val directory = File(activity.cacheDir, "verified-updates")
        val pendingName = installState.getString(PENDING_APK, null)
        val pending = pendingName?.let { APK_NAME.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
        val pendingSize = installState.getLong(PENDING_SIZE, 0L)
        val pendingHash = installState.getString(PENDING_HASH, null)
        if (pending != null && pending > installedVersion && pendingSize in 1..MAX_APK_BYTES &&
            pendingHash != null && HASH.matches(pendingHash)) {
            val spec = ApkSpec(pending, pendingSize, pendingHash)
            val saved = File(directory, spec.filename)
            if (isValidApk(saved, installed, spec)) {
                return State.Ready(VerifiedUpdate(spec, saved), pending = true)
            }
        }

        val release = latestAsset()
        val spec = release.spec
        if (spec.version <= installedVersion) {
            Log.i(LOG_TAG, "Installed version is up to date")
            return State.Notice(R.string.update_current)
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Update storage is unavailable")
        val apk = File(directory, spec.filename)
        if (!isValidApk(apk, installed, spec)) download(release, installed, apk)
        // Resume only the APK the user selected. A different release needs its own confirmation.
        return State.Ready(VerifiedUpdate(spec, apk), pending = pendingName == apk.name)
    }

    private fun isValidApk(apk: File, installed: PackageInfo, spec: ApkSpec): Boolean =
        apk.isFile && apk.canRead() && apk.length() == spec.size &&
            apk.inputStream().use { sha256(it) } == spec.sha256 && matchesInstalledApp(apk, installed, spec)

    private fun download(release: ReleaseAsset, installed: PackageInfo, apk: File) {
        val partial = File.createTempFile("amin-", ".apk", activity.cacheDir)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            val size = fetch(release.url) { input ->
                partial.outputStream().use { output ->
                    copyBounded(DigestInputStream(input, hash), output, release.spec.size)
                }
            }
            if (size != release.spec.size || hex(hash.digest()) != release.spec.sha256 ||
                !matchesInstalledApp(partial, installed, release.spec)) throw InvalidUpdate()
            if (!partial.renameTo(apk)) throw IOException("Could not save verified APK")
            apk.parentFile?.listFiles()?.filter { it != apk && APK_NAME.matches(it.name) }?.forEach { it.delete() }
        } finally {
            partial.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun matchesInstalledApp(file: File, installed: PackageInfo, spec: ApkSpec): Boolean {
        val archive = activity.packageManager.getPackageArchiveInfo(file.path, signingFlags()) ?: return false
        val signer = signerDigests(archive)
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) archive.applicationInfo?.minSdkVersion else null
        return archive.packageName == installed.packageName &&
            versionOf(archive) == spec.version &&
            (minSdk == null || minSdk <= Build.VERSION.SDK_INT) &&
            signer.isNotEmpty() && signer == signerDigests(installed)
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map {
            hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return hex(digest.digest())
            digest.update(buffer, 0, count)
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return size
            size += count
            check(size <= limit) { "Unexpected response size" }
            output.write(buffer, 0, count)
        }
    }

    private fun <T> fetch(start: URL, read: (InputStream) -> T): T {
        var url = start
        repeat(4) {
            check(url.protocol == "https" && url.host.lowercase(Locale.ROOT) in GITHUB_HOSTS &&
                url.userInfo == null && url.port in listOf(-1, 443))
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Amin-player")
                if (url.host == "api.github.com") setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                when (val code = connection.responseCode) {
                    200 -> return connection.inputStream.use(read)
                    301, 302, 303, 307, 308 -> url = URL(url, checkNotNull(connection.getHeaderField("Location")))
                    else -> error("GitHub returned $code")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Too many GitHub redirects")
    }

    private companion object {
        const val LOG_TAG = "AminUpdater"
        const val REPOSITORY = "Skyspiriit/Amin-Player"
        const val PENDING_APK = "pending_apk"
        const val PENDING_SIZE = "pending_size"
        const val PENDING_HASH = "pending_sha256"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val MAX_METADATA_BYTES = 1024L * 1024
        const val MAX_APK_BYTES = 32L * 1024 * 1024
        val TAG = Regex("v[0-9]+(?:\\.[0-9]+){1,2}")
        val APK_NAME = Regex("Amin-player-([0-9]+)\\.apk")
        val SHA256 = Regex("sha256:[0-9a-fA-F]{64}")
        val HASH = Regex("[0-9a-f]{64}")
        val GITHUB_HOSTS = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }
}
