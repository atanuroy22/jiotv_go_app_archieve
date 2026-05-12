package com.skylake.skytv.jgorunner.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.skylake.skytv.jgorunner.ui.tvhome.CloudChannel
import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                val type = object : TypeToken<List<CloudServer>>() {}.type
                gson.fromJson<List<CloudServer>>(body, type) ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e("CloudRepository", "Error fetching servers", e)
            emptyList()
        }
    }

    suspend fun fetchChannels(url: String, forceRefresh: Boolean = false): List<CloudChannel> = withContext(Dispatchers.IO) {
        // Special handling for Localhost Binary (M3U or JSON)
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
                cacheFile.writeText(body)
                val type = object : TypeToken<List<CloudChannel>>() {}.type
                try {
                    gson.fromJson<List<CloudChannel>>(body, type) ?: emptyList<CloudChannel>()
                } catch (e: Exception) {
                    Log.e("CloudRepository", "Parse failed for $url", e)
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e("CloudRepository", "Fetch failed for $url", e)
            emptyList()
        }
    }

    private suspend fun fetchLocalChannels(url: String): List<CloudChannel> = withContext(Dispatchers.IO) {
        try {
            // Try fetching as JSON first (if the binary supports it)
            val jsonUrl = url.replace("playlist.m3u", "channels.json")
            val request = Request.Builder().url(jsonUrl).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val type = object : TypeToken<List<CloudChannel>>() {}.type
                    return@use gson.fromJson<List<CloudChannel>>(body, type) ?: emptyList<CloudChannel>()
                }
            }
        } catch (_: Exception) {}

        try {
            // Fallback: Parse M3U and map to CloudChannel
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: ""
                return@use parseM3U(body, url)
            }
        } catch (e: Exception) {
            Log.e("CloudRepository", "Local fetch failed", e)
            emptyList()
        }
    }

    private fun parseM3U(m3u: String, baseUrl: String): List<CloudChannel> {
        val list = mutableListOf<CloudChannel>()
        val lines = m3u.split("\n")
        var currentName = ""
        var currentLogo = ""
        var currentGroup = ""

        lines.forEach { line ->
            if (line.startsWith("#EXTINF")) {
                currentName = line.substringAfter("tvg-name=\"").substringBefore("\"")
                if (currentName == line) currentName = line.substringAfter(",")
                currentLogo = line.substringAfter("tvg-logo=\"").substringBefore("\"")
                currentGroup = line.substringAfter("group-title=\"").substringBefore("\"")
            } else if (line.startsWith("http")) {
                list.add(CloudChannel(
                    type = if (line.contains(".mpd")) "dash" else "hls",
                    id = line.hashCode().toString(),
                    name = currentName,
                    group = currentGroup,
                    language = "Hindi",
                    logo = currentLogo,
                    userAgent = "JioTV",
                    mpdUrl = if (line.contains(".mpd")) line else null,
                    m3u8Url = if (!line.contains(".mpd")) line else null,
                    licenseUrl = null,
                    headers = null,
                    expiresIn = null
                ))
            }
        }
        return list
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { if (it.name.startsWith("cloud_channels_")) it.delete() }
    }
}
