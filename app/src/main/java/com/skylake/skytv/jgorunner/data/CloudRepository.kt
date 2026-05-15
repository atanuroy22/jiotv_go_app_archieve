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
                CloudParsers.parseServerList(gson, body)
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
                    return@withContext CloudParsers.parseM3U(body, url, localBaseServerUrl = null)
                }

                cacheFile.writeText(body)
                val type = object : TypeToken<List<CloudChannel>>() {}.type
                try {
                    val list = gson.fromJson<List<CloudChannel>>(body, type)
                    if (list != null) return@use list

                    // Try parsing as a map if it's nested
                    val mapType = object : TypeToken<Map<String, Any>>() {}.type
                    val map = gson.fromJson<Map<String, Any>>(body, mapType)
                    val nestedChannels = CloudParsers.unwrapChannelContainer(map["channels"] ?: map["data"] ?: map["list"] ?: map["items"])
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
                        val nestedChannels = CloudParsers.unwrapChannelContainer(map["channels"] ?: map["data"] ?: map["list"] ?: map["items"])
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
                        return@withContext CloudParsers.parseM3U(body, url, localBaseServerUrl())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CloudRepository", "Local M3U fetch failed", e)
        }
        emptyList()
    }

    private fun localBaseServerUrl(): String =
        "http://localhost:${SkySharedPref.getInstance(context).myPrefs.jtvGoServerPort}"

    fun clearCache() {
        cacheDir.listFiles()?.forEach { if (it.name.startsWith("cloud_channels_")) it.delete() }
    }
}
