package com.skylake.skytv.jgorunner.data

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.skylake.skytv.jgorunner.ui.tvhome.CloudChannel
import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object CloudParsers {
    private val tvgNameRegex = Regex("""tvg-name="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val tvgLogoRegex = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val groupTitleRegex = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val tvgLanguageRegex = Regex("""tvg-language="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val languageRegex = Regex("""language="([^"]+)"""", RegexOption.IGNORE_CASE)

    fun parseServerList(gson: Gson, body: String): List<CloudServer> {
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

    fun unwrapChannelContainer(value: Any?): List<*>? {
        var current: Any? = value
        repeat(5) {
            current = when (current) {
                is List<*> -> return current
                is Map<*, *> -> (current as Map<*, *>)["channels"]
                    ?: (current as Map<*, *>)["data"]
                    ?: (current as Map<*, *>)["list"]
                    ?: (current as Map<*, *>)["items"]
                else -> return null
            }
        }
        return current as? List<*>
    }

    fun parseM3U(m3u: String, playlistUrl: String, localBaseServerUrl: String?): List<CloudChannel> {
        val list = mutableListOf<CloudChannel>()
        val lines = m3u.split("\n")
        var currentName = ""
        var currentLogo = ""
        var currentGroup = ""
        var currentLanguage = ""

        val indianLanguages = listOf(
            "Hindi",
            "English",
            "Tamil",
            "Telugu",
            "Malayalam",
            "Kannada",
            "Bengali",
            "Marathi",
            "Gujarati",
            "Punjabi",
            "Urdu",
            "Odia",
            "Assamese"
        )

        lines.forEach { rawLine ->
            val line = rawLine.trim().trimStart('\uFEFF')
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val nameFromTag = tvgNameRegex
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    .orEmpty()
                val nameFromComma = line.substringAfterLast(",", "").trim()
                currentName = nameFromTag.ifBlank { nameFromComma }
                currentLogo = tvgLogoRegex
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    .orEmpty()
                currentGroup = groupTitleRegex
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    .orEmpty()

                val langMatch = tvgLanguageRegex.find(line) ?: languageRegex.find(line)
                val langTag = langMatch?.groupValues?.get(1)

                if (!langTag.isNullOrBlank()) {
                    currentLanguage = langTag
                } else {
                    val found = indianLanguages.filter { currentName.contains(it, ignoreCase = true) }
                    currentLanguage = if (found.isNotEmpty()) found.distinct().joinToString(", ") else "Hindi"
                }
            } else {
                if (line.isBlank() || line.startsWith("#")) return@forEach

                val streamUrl = resolveAgainstPlaylistUrl(playlistUrl, line) ?: line
                if (!streamUrl.startsWith("http", ignoreCase = true)) return@forEach

                val channelId = streamUrl
                    .substringAfterLast("/")
                    .substringBefore("?")
                    .substringBefore(".")
                    .ifBlank { currentName.trim().ifBlank { streamUrl.hashCode().toString() } }

                val finalName = currentName.trim().ifBlank { channelId }
                val finalGroup = if (currentGroup.isBlank()) "General" else currentGroup.trim()
                val finalLanguage = currentLanguage.trim().ifBlank { "Hindi" }

                val resolvedLogo = when {
                    currentLogo.isBlank() -> ""
                    currentLogo.startsWith("http", ignoreCase = true) -> currentLogo
                    localBaseServerUrl != null -> "$localBaseServerUrl/jtvimage/$currentLogo"
                    else -> resolveAgainstPlaylistUrl(playlistUrl, currentLogo) ?: currentLogo
                }

                val playbackUrl = if (localBaseServerUrl != null) "$localBaseServerUrl/play/$channelId" else streamUrl
                val inferredType = if (localBaseServerUrl != null) "dash" else "hls"

                list.add(
                    CloudChannel(
                        type = inferredType,
                        id = channelId,
                        name = finalName,
                        group = finalGroup,
                        language = finalLanguage,
                        logo = resolvedLogo,
                        userAgent = "JioTV",
                        mpdUrl = playbackUrl,
                        m3u8Url = streamUrl,
                        licenseUrl = null,
                        headers = null,
                        expiresIn = null
                    )
                )
            }
        }

        return list
    }

    fun resolveAgainstPlaylistUrl(playlistUrl: String, candidate: String): String? {
        if (candidate.startsWith("http", ignoreCase = true)) return candidate
        val base = playlistUrl.toHttpUrlOrNull() ?: return null
        return base.resolve(candidate)?.toString()
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

        // Some lists (including Hotstar) wrap the array under an arbitrary top-level key.
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
}
