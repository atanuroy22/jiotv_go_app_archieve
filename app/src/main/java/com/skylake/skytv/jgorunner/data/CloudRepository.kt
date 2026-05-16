package com.skylake.skytv.jgorunner.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
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
                CloudParsers.parseServerList(gson, body)
            }
        } catch (e: Exception) {
            Log.e("CloudRepository", "Error fetching servers", e)
            emptyList()
        }
    }

    suspend fun fetchChannels(url: String, forceRefresh: Boolean = false): List<CloudChannel> =
        fetchChannelsInternal(url, forceRefresh, visited = mutableSetOf(), depth = 3)

    private suspend fun fetchChannelsInternal(
        url: String,
        forceRefresh: Boolean,
        visited: MutableSet<String>,
        depth: Int
    ): List<CloudChannel> = withContext(Dispatchers.IO) {
        if (depth <= 0) return@withContext emptyList()
        if (!visited.add(url)) return@withContext emptyList()

        if (url.contains("localhost") || url.contains("127.0.0.1")) {
            return@withContext filterCloudChannels(fetchLocalChannels(url))
        }

        val cacheFile = File(cacheDir, "cloud_channels_${url.hashCode()}.json")
        val cachedBody = if (!forceRefresh && cacheFile.exists()) {
            val lastModified = cacheFile.lastModified()
            if (System.currentTimeMillis() - lastModified < TimeUnit.HOURS.toMillis(1)) {
                try {
                    cacheFile.readText()
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        } else {
            null
        }

        val body = cachedBody ?: try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val text = response.body?.string() ?: return@withContext emptyList()
                try {
                    cacheFile.writeText(text)
                } catch (_: Exception) {}
                text
            }
        } catch (e: Exception) {
            Log.e("CloudRepository", "Fetch failed for $url", e)
            return@withContext emptyList()
        }

        if (body.contains("#EXTM3U", ignoreCase = true)) {
            return@withContext filterCloudChannels(CloudParsers.parseM3U(body, url, localBaseServerUrl = null))
        }

        val jsonChannels = CloudParsers.parseChannelList(gson, body)
        if (jsonChannels.isNotEmpty()) {
            return@withContext filterCloudChannels(jsonChannels)
        }

        val servers = CloudParsers.parseServerList(gson, body)
        if (servers.isNotEmpty()) {
            val merged = servers.flatMap { server ->
                fetchChannelsInternal(server.url, forceRefresh, visited, depth - 1)
            }
            return@withContext filterCloudChannels(merged)
        }

        val nestedUrl = CloudParsers.extractFirstUrl(body)
        if (!nestedUrl.isNullOrBlank() && !nestedUrl.equals(url, true)) {
            return@withContext fetchChannelsInternal(nestedUrl, forceRefresh, visited, depth - 1)
        }

        emptyList()
    }

    private suspend fun fetchLocalChannels(url: String): List<CloudChannel> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    if (body.contains("#EXTM3U")) {
                        return@withContext CloudParsers.parseM3U(body, url, localBaseServerUrl())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CloudRepository", "Local M3U fetch failed", e)
        }
        emptyList()
    }

    private fun filterCloudChannels(channels: List<CloudChannel>): List<CloudChannel> {
        return channels.filterNot { it.name.equals("cloudchannel", ignoreCase = true) }
    }

    private fun localBaseServerUrl(): String =
        "http://localhost:${SkySharedPref.getInstance(context).myPrefs.jtvGoServerPort}"

    fun clearCache() {
        cacheDir.listFiles()?.forEach { if (it.name.startsWith("cloud_channels_")) it.delete() }
    }
}
