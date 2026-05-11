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
        val cacheFile = File(cacheDir, "cloud_channels_${url.hashCode()}.json")

        if (!forceRefresh && cacheFile.exists()) {
            val lastModified = cacheFile.lastModified()
            val now = System.currentTimeMillis()
            if (now - lastModified < TimeUnit.HOURS.toMillis(1)) {
                try {
                    val body = cacheFile.readText()
                    val type = object : TypeToken<List<CloudChannel>>() {}.type
                    return@withContext gson.fromJson<List<CloudChannel>>(body, type) ?: emptyList()
                } catch (e: Exception) {
                    Log.e("CloudRepository", "Error reading cache", e)
                }
            }
        }

        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()

                // Cache the response
                cacheFile.writeText(body)

                val type = object : TypeToken<List<CloudChannel>>() {}.type
                try {
                    gson.fromJson<List<CloudChannel>>(body, type) ?: emptyList()
                } catch (e: Exception) {
                    Log.e("CloudRepository", "Direct parse failed, trying object wrap", e)
                    // If the JSON is an object { "channels": [...] } or similar, we might need a more flexible parser
                    // For now, let's just log the body to see what's wrong
                    Log.d("CloudRepository", "Problematic JSON: ${body.take(500)}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e("CloudRepository", "Error fetching channels", e)
            emptyList()
        }
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach {
            if (it.name.startsWith("cloud_channels_")) {
                it.delete()
            }
        }
    }
}
