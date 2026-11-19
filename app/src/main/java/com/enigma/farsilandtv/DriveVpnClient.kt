package com.enigma.farsilandtv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class DriveVpnFile(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val parent: String? = null,
    val modified: String = ""
)

internal enum class DriveVpnFailure { AUTHORIZE, SETUP, NETWORK, EMPTY, FORMAT }
internal class DriveVpnProblem(val reason: DriveVpnFailure) : Exception("Drive import failed")

internal object DriveVpnQuery {
    const val FOLDER_MIME = "application/vnd.google-apps.folder"
    const val FOLDERS = "trashed = false and mimeType = 'application/vnd.google-apps.folder' and name = 'VPN'"
    fun children(folder: String): String {
        require(folder.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid folder selection." }
        return "trashed = false and '$folder' in parents and mimeType != '$FOLDER_MIME'"
    }
}

// Tokens stay in memory and are sent only to this fixed Google API origin.
// The broader OAuth scope is narrowed here to exact-name VPN folders and their children.
internal class DriveVpnClient(private val token: String) {
    fun folders(): List<DriveVpnFile> = list(DriveVpnQuery.FOLDERS)

    fun files(folder: DriveVpnFile): List<DriveVpnFile> {
        require(folder.name == "VPN" && folder.mime == DriveVpnQuery.FOLDER_MIME)
        return list(DriveVpnQuery.children(folder.id)).filter { file ->
            file.size <= SubscriptionAddress.MAX_BYTES &&
                (file.mime.startsWith("text/") || file.mime == "application/json" ||
                    file.name.substringAfterLast('.', "").lowercase() in setOf("txt", "json", "conf", "vless"))
        }
    }

    fun parentName(folder: DriveVpnFile): String {
        val id = folder.parent ?: return "Google Drive"
        val json = JSONObject(request("files/${encode(id)}?fields=name&supportsAllDrives=true"))
        return json.optString("name", "Google Drive")
    }

    fun read(folder: DriveVpnFile, file: DriveVpnFile): String {
        // Re-check parent membership at import time in case a selected file moved.
        val selected = files(folder).firstOrNull { it.id == file.id }
            ?: throw DriveVpnProblem(DriveVpnFailure.EMPTY)
        return request("files/${encode(selected.id)}?alt=media&supportsAllDrives=true")
    }

    private fun list(query: String): List<DriveVpnFile> {
        val result = ArrayList<DriveVpnFile>()
        var next: String? = null
        do {
            val path = "files?spaces=drive&supportsAllDrives=true&includeItemsFromAllDrives=true" +
                "&pageSize=100&orderBy=name&q=${encode(query)}" +
                "&fields=${encode("nextPageToken,files(id,name,mimeType,size,parents,modifiedTime)")}" +
                (next?.let { "&pageToken=${encode(it)}" } ?: "")
            val json = JSONObject(request(path))
            val files = json.optJSONArray("files") ?: throw DriveVpnProblem(DriveVpnFailure.FORMAT)
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                result.add(DriveVpnFile(item.getString("id"), item.getString("name"),
                    item.getString("mimeType"), item.optLong("size", 0),
                    item.optJSONArray("parents")?.optString(0)?.takeIf(String::isNotBlank),
                    item.optString("modifiedTime")))
            }
            next = json.optString("nextPageToken").takeIf(String::isNotBlank)
        } while (next != null)
        return result
    }

    private fun request(path: String): String {
        val connection = URL("https://www.googleapis.com/drive/v3/$path")
            .openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json, text/plain")
            when (connection.responseCode) {
                200 -> return connection.inputStream.use {
                    SubscriptionAddress.readBounded(it).toString(Charsets.UTF_8)
                }
                401 -> throw DriveVpnProblem(DriveVpnFailure.AUTHORIZE)
                403 -> throw DriveVpnProblem(DriveVpnFailure.SETUP)
                404 -> throw DriveVpnProblem(DriveVpnFailure.EMPTY)
                else -> throw DriveVpnProblem(DriveVpnFailure.NETWORK)
            }
        } catch (e: DriveVpnProblem) {
            throw e
        } catch (_: Exception) {
            throw DriveVpnProblem(DriveVpnFailure.NETWORK)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
