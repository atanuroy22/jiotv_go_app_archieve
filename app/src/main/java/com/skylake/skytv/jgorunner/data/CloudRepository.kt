package com.skylake.skytv.jgorunner.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.skylake.skytv.jgorunner.ui.tvhome.CloudChannel
import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class CloudRepository(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val cacheDir = context.cacheDir

    suspend fun fetchServers(url: String): List<CloudServer> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()
                parseServerList(body)
            }
        } catch (e: Exception) {
            Log.e("CloudRepository", "Error fetching servers", e)
            emptyList()
        }
    }

    suspend fun fetchChannels(url: String, forceRefresh: Boolean = false): List<CloudChannel> = withContext(Dispatchers.IO) {
        if (url.contains("localhost") || url.contains("127.0.0.1")) {
            return@withContext fetchLocalChannels(url)
        }

        val cacheFile = File(cacheDir, "cloud_channels_${url.hashCode()}.json")
        if (!forceRefresh && cacheFile.exists()) {
            val lastModified = cacheFile.lastModified()
            if (System.currentTimeMillis() - lastModified < TimeUnit.HOURS.toMillis(1)) {
                try {
                    val body = cacheFile.readText()
                    val type = object : TypeToken<List<CloudChannel>>() {}.type
                    return@withContext gson.fromJson<List<CloudChannel>>(body, type) ?: emptyList()
                } catch (_: Exception) {}
            }
        }

        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()

                if (body.contains("#EXTM3U")) {
                    return@withContext parseM3U(body, url)
                }

                cacheFile.writeText(body)
                val type = object : TypeToken<List<CloudChannel>>() {}.type
                try {
                    val list = gson.fromJson<List<CloudChannel>>(body, type)
                    if (list != null) return@use list

                    // Try parsing as a map if it's nested
                    val mapType = object : TypeToken<Map<String, Any>>() {}.type
                    val map = gson.fromJson<Map<String, Any>>(body, mapType)
                    val nestedChannels = unwrapChannelContainer(map["channels"] ?: map["data"] ?: map["list"] ?: map["items"])
                    nestedChannels?.let { nested ->
                        val nestedJson = gson.toJson(nested)
                        return@use gson.fromJson<List<CloudChannel>>(nestedJson, type) ?: emptyList()
                    }

                    emptyList()
                } catch (e: Exception) {
                    Log.e("CloudRepository", "Parse failed for $url. Body snippet: ${body.take(100)}")
                    // One last attempt for extreme cases
                    try {
                        val mapType = object : TypeToken<Map<String, Any>>() {}.type
                        val map = gson.fromJson<Map<String, Any>>(body, mapType)
                        val nestedChannels = unwrapChannelContainer(map["channels"] ?: map["data"] ?: map["list"] ?: map["items"])
                        nestedChannels?.let { nested ->
                            val nestedJson = gson.toJson(nested)
                            return@use gson.fromJson<List<CloudChannel>>(nestedJson, type) ?: emptyList()
                        }
                        emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CloudRepository", "Fetch failed for $url", e)
            emptyList()
        }
    }

    private suspend fun fetchLocalChannels(url: String): List<CloudChannel> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    if (body.contains("#EXTM3U")) {
                        return@withContext parseM3U(body, url)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CloudRepository", "Local M3U fetch failed", e)
        }
        emptyList()
    }

    private fun unwrapChannelContainer(value: Any?): Any? {
        return when (value) {
            is List<*> -> value
            is Map<*, *> -> {
                value["channels"] ?: value["data"] ?: value["list"] ?: value["items"]
            }
            else -> null
        }
    }

    private fun parseServerList(body: String): List<CloudServer> {
        val directType = object : TypeToken<List<CloudServer>>() {}.type
        try {
            val direct = gson.fromJson<List<CloudServer>>(body, directType)
            if (!direct.isNullOrEmpty()) return direct
        } catch (_: Exception) {
            // Fall back to flexible parsing below.
        }

        val root = try {
            JsonParser.parseString(body)
        } catch (_: Exception) {
            return emptyList()
        }

        val arr = findFirstJsonArray(root) ?: return emptyList()
        val mapped = arr.mapNotNull { el ->
            val obj = el.asJsonObjectOrNull() ?: return@mapNotNull null
            val name = obj.firstStringOf("name", "title", "server", "label")
                ?: obj.firstStringOf("server_name", "serverName")
            val url = obj.firstStringOf("url", "link", "playlist", "playlist_url", "playlistUrl", "m3u", "m3u_url", "m3uUrl")
                ?: obj.firstStringOf("base_url", "baseUrl", "endpoint")
            val logo = obj.firstStringOf("logo", "icon", "image", "poster", "thumb", "thumbnail") ?: ""

            val finalName = name?.trim().orEmpty()
            val finalUrl = url?.trim().orEmpty()
            if (finalName.isBlank() || finalUrl.isBlank()) return@mapNotNull null
            CloudServer(name = finalName, url = finalUrl, logo = logo.trim())
        }

        return mapped.distinctBy { it.url }
    }

    private fun findFirstJsonArray(root: JsonElement): JsonArray? {
        if (root.isJsonArray) return root.asJsonArray
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject

        listOf("servers", "data", "list", "items", "result", "results").forEach { key ->
            obj.get(key)?.let { el ->
                if (el.isJsonArray) return el.asJsonArray
                if (el.isJsonObject) {
                    findFirstJsonArray(el)?.let { return it }
                }
            }
        }

        // Hotstar JSON sometimes wraps the array under an arbitrary top-level key.
        obj.entrySet().forEach { (_, v) ->
            if (v.isJsonArray) return v.asJsonArray
            if (v.isJsonObject) {
                findFirstJsonArray(v)?.let { return it }
            }
        }
        return null
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.firstStringOf(vararg keys: String): String? {
        for (key in keys) {
            val el = get(key) ?: continue
            if (el.isJsonPrimitive && el.asJsonPrimitive.isString) return el.asString
            if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) return el.asNumber.toString()
        }
        return null
    }

    private fun parseM3U(m3u: String, baseUrl: String): List<CloudChannel> {
        val list = mutableListOf<CloudChannel>()
        val lines = m3u.split("\n")
        var currentName = ""
        var currentLogo = ""
        var currentGroup = ""
        var currentLanguage = ""

        val indianLanguages = listOf("Hindi", "English", "Tamil", "Telugu", "Malayalam", "Kannada", "Bengali", "Marathi", "Gujarati", "Punjabi", "Urdu", "Odia", "Assamese")
        val isLocalPlaylist = baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")
        val baseServerUrl = if (isLocalPlaylist) {
            "http://localhost:${SkySharedPref.getInstance(context).myPrefs.jtvGoServerPort}"
        } else {
            null
        }

        lines.forEach { line ->
            if (line.startsWith("#EXTINF")) {
                currentName = line.substringAfter("tvg-name=\"").substringBefore("\"")
                if (currentName == line) currentName = line.substringAfter(",")
                currentLogo = line.substringAfter("tvg-logo=\"").substringBefore("\"")
                currentGroup = line.substringAfter("group-title=\"").substringBefore("\"")

                val langMatch = Regex("""tvg-language="([^"]+)"""").find(line) ?: Regex("""language="([^"]+)"""").find(line)
                val langTag = langMatch?.groupValues?.get(1)

                if (!langTag.isNullOrBlank()) {
                    currentLanguage = langTag
                } else {
                    val found = indianLanguages.filter { currentName.contains(it, ignoreCase = true) }
                    currentLanguage = if (found.isNotEmpty()) found.distinct().joinToString(", ") else "Hindi"
                }

            } else {
                val candidate = line.trim()
                if (candidate.isBlank() || candidate.startsWith("#")) return@forEach

                val m3u8Url = resolveAgainstPlaylistUrl(baseUrl, candidate) ?: candidate
                if (!m3u8Url.startsWith("http", ignoreCase = true)) return@forEach

                val channelId = m3u8Url
                    .substringAfterLast("/")
                    .substringBefore("?")
                    .substringBefore(".")
                    .ifBlank { currentName.trim().ifBlank { m3u8Url.hashCode().toString() } }

                val resolvedLogo = when {
                    currentLogo.isBlank() -> ""
                    currentLogo.startsWith("http", ignoreCase = true) -> currentLogo
                    baseServerUrl != null -> "$baseServerUrl/jtvimage/$currentLogo"
                    else -> resolveAgainstPlaylistUrl(baseUrl, currentLogo) ?: currentLogo
                }

                val playUrl = if (baseServerUrl != null) "$baseServerUrl/play/$channelId" else m3u8Url
                val inferredType = if (baseServerUrl != null) "dash" else "hls"
                list.add(CloudChannel(
                    type = inferredType,
                    id = channelId,
                    name = currentName.trim(),
                    group = if (currentGroup.isBlank()) "General" else currentGroup.trim(),
                    language = currentLanguage.trim(),
                    logo = resolvedLogo,
                    userAgent = "JioTV",
                    mpdUrl = playUrl,
                    m3u8Url = m3u8Url,
                    licenseUrl = null,
                    headers = null,
                    expiresIn = null
                ))
            }
        }
        return list
    }

    private fun resolveAgainstPlaylistUrl(playlistUrl: String, candidate: String): String? {
        if (candidate.startsWith("http", ignoreCase = true)) return candidate
        val base = playlistUrl.toHttpUrlOrNull() ?: return null
        return base.resolve(candidate)?.toString()
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { if (it.name.startsWith("cloud_channels_")) it.delete() }
    }
}
