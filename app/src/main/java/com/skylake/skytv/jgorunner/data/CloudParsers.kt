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
    private val tvgLanguageRegex = Regex("""tvg-language="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val languageRegex = Regex("""language="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val extVlcOptRegex = Regex("""#EXTVLCOPT:([^=]+)=(.*)""", RegexOption.IGNORE_CASE)
    private val urlRegex = Regex("""https?://[^\s"']+""", RegexOption.IGNORE_CASE)

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
        val lines = m3u.trimStart('\uFEFF').split("\n")
        var currentName = ""
        var currentLogo = ""
        var currentGroup = ""
        var currentLanguage = ""
        var currentUserAgent = ""
        val currentHeaders = mutableMapOf<String, String>()

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
            val line = rawLine.trim()
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val nameFromTag = tvgNameRegex
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    .orEmpty()
                val nameFromComma = line.substringAfter(",", "").trim()
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

                currentUserAgent = ""
                currentHeaders.clear()

                val langMatch = tvgLanguageRegex.find(line) ?: languageRegex.find(line)
                val langTag = langMatch?.groupValues?.get(1)

                if (!langTag.isNullOrBlank()) {
                    currentLanguage = langTag
                } else {
                    val found = indianLanguages.filter { currentName.contains(it, ignoreCase = true) }
                    currentLanguage = if (found.isNotEmpty()) found.distinct().joinToString(", ") else "Hindi"
                }
            } else if (line.startsWith("#EXTVLCOPT", ignoreCase = true)) {
                val match = extVlcOptRegex.find(line)
                if (match != null) {
                    val key = match.groupValues[1].trim()
                    val value = match.groupValues[2].trim()
                    when {
                        key.equals("http-user-agent", true) || key.equals("user-agent", true) -> {
                            currentUserAgent = value
                            if (!currentHeaders.containsKey("User-Agent")) {
                                currentHeaders["User-Agent"] = value
                            }
                        }
                        key.equals("http-referrer", true) || key.equals("referrer", true) || key.equals("referer", true) -> {
                            if (!currentHeaders.containsKey("Referer")) {
                                currentHeaders["Referer"] = value
                            }
                        }
                    }
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

                val headersPayload = currentHeaders.takeIf { it.isNotEmpty() }
                val userAgentPayload = currentUserAgent.trim().ifBlank { null }

                list.add(
                    CloudChannel(
                        type = inferredType,
                        id = channelId,
                        name = finalName,
                        group = finalGroup,
                        language = finalLanguage,
                        logo = resolvedLogo,
                        userAgent = userAgentPayload ?: "JioTV",
                        mpdUrl = playbackUrl,
                        m3u8Url = streamUrl,
                        licenseUrl = null,
                        headers = headersPayload,
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

    fun parseChannelList(gson: Gson, body: String): List<CloudChannel> {
        val root = try {
            JsonParser.parseString(body)
        } catch (_: Exception) {
            return emptyList()
        }

        val arr = findFirstJsonArray(root) ?: return emptyList()
        val items = arr.mapNotNull { el ->
            val obj = el.asJsonObjectOrNull() ?: return@mapNotNull null

            val name = obj.firstStringOf("name", "title", "channel", "channel_name", "channelName")
            val id = obj.firstStringOf("id", "channel_id", "channelId", "cid")
            val group = obj.firstStringOf("group", "category", "group_title", "groupTitle")
            val language = obj.firstStringOf("language", "lang", "tvg_language", "tvg-language")
            val logo = obj.firstStringOf(
                "logo",
                "icon",
                "image",
                "poster",
                "thumb",
                "thumbnail",
                "tvg_logo",
                "tvg-logo",
                "src"
            )
            val type = obj.firstStringOf("type", "stream_type", "streamType", "format")
            val userAgent = obj.firstStringOf("user_agent", "userAgent", "ua", "user-agent", "User-Agent")

            val mpdRaw = obj.firstStringOf("mpd_url", "mpd", "dash", "dash_url", "mpdUrl")
            val adFreeRaw = obj.firstStringOf("adfree_url", "adfreeUrl", "adfree")
            val daiRaw = obj.firstStringOf("dai_url", "daiUrl", "dai")
            val m3uRaw = obj.firstStringOf(
                "m3u8_url",
                "m3u8",
                "hls",
                "hls_url",
                "m3u_url",
                "m3uUrl",
                "url",
                "link",
                "stream_url",
                "streamUrl",
                "play_url",
                "playUrl"
            )

            val rawUrl = m3uRaw?.trim().orEmpty()
            val isPlaylistLink = rawUrl.isNotBlank() && (
                rawUrl.contains(".json", true) ||
                    rawUrl.endsWith(".txt", true) ||
                    (rawUrl.endsWith(".m3u", true) && !rawUrl.endsWith(".m3u8", true)) ||
                    rawUrl.contains("playlist", true)
                )

            val licenseUrl = obj.firstStringOf("license_url", "licenseUrl", "license", "drm_license", "drm", "license_url")

            val headers = obj.get("headers")?.let { headersElement ->
                when {
                    headersElement.isJsonObject -> {
                        headersElement.asJsonObject.entrySet().mapNotNull { (k, v) ->
                            val value = if (v.isJsonPrimitive) v.asJsonPrimitive.asString else null
                            value?.let { k to it }
                        }.toMap()
                    }
                    headersElement.isJsonArray -> {
                        headersElement.asJsonArray.mapNotNull { el ->
                            if (!el.isJsonPrimitive) return@mapNotNull null
                            val line = el.asString
                            val parts = line.split(":", limit = 2)
                            if (parts.size < 2) return@mapNotNull null
                            parts[0].trim() to parts[1].trim()
                        }.toMap()
                    }
                    headersElement.isJsonPrimitive -> {
                        val raw = headersElement.asString
                        raw.split("\n", ";").mapNotNull { line ->
                            val parts = line.split(":", limit = 2)
                            if (parts.size < 2) return@mapNotNull null
                            parts[0].trim() to parts[1].trim()
                        }.toMap()
                    }
                    else -> null
                }
            }

            val expiresIn = obj.firstStringOf("expires_in", "expiresIn", "expiry", "exp")

            if (isPlaylistLink && mpdRaw.isNullOrBlank() && type.isNullOrBlank() && licenseUrl.isNullOrBlank()) {
                // Likely a server entry rather than a channel.
                return@mapNotNull null
            }

            val resolvedMpd = when {
                !mpdRaw.isNullOrBlank() -> mpdRaw
                !m3uRaw.isNullOrBlank() && m3uRaw.contains(".mpd", true) -> m3uRaw
                type?.contains("dash", true) == true && !m3uRaw.isNullOrBlank() -> m3uRaw
                else -> null
            }

            val resolvedM3u8 = when {
                !m3uRaw.isNullOrBlank() && m3uRaw.contains(".m3u", true) -> m3uRaw
                resolvedMpd == null && !m3uRaw.isNullOrBlank() -> m3uRaw
                else -> null
            }

            val primaryHls = adFreeRaw?.trim().orEmpty().ifBlank { daiRaw?.trim().orEmpty() }
            val fallbackHls = if (!adFreeRaw.isNullOrBlank() && !daiRaw.isNullOrBlank()) {
                daiRaw?.trim()
            } else {
                null
            }
            val finalPrimaryHls = primaryHls.ifBlank { resolvedM3u8?.trim().orEmpty() }.ifBlank { null }
            val finalFallbackHls = fallbackHls?.takeIf { it.isNotBlank() && it != finalPrimaryHls }

            val finalId = id?.trim().orEmpty().ifBlank {
                resolvedM3u8?.substringAfterLast("/")?.substringBefore("?")?.substringBefore(".")
                    ?: resolvedMpd?.substringAfterLast("/")?.substringBefore("?")?.substringBefore(".")
                    ?: ""
            }
            val finalName = name?.trim().orEmpty().ifBlank {
                finalId.ifBlank { "Unknown" }
            }

            if (finalName.isBlank() || (resolvedMpd.isNullOrBlank() && finalPrimaryHls.isNullOrBlank())) {
                return@mapNotNull null
            }

            CloudChannel(
                type = type,
                id = finalId,
                name = finalName,
                group = group?.trim(),
                language = language?.trim(),
                logo = logo?.trim(),
                userAgent = userAgent?.trim(),
                mpdUrl = resolvedMpd?.trim() ?: finalPrimaryHls,
                m3u8Url = finalFallbackHls ?: resolvedM3u8?.trim(),
                licenseUrl = licenseUrl?.trim(),
                headers = headers,
                expiresIn = expiresIn?.trim()
            )
        }

        return items.distinctBy { it.id?.ifBlank { it.name } ?: it.name }
    }

    fun extractFirstUrl(body: String): String? {
        return urlRegex.find(body)?.value
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
