package com.skylake.skytv.jgorunner.tata

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal class TataHttpServer(
    private val baseDir: File,
    port: Int
) : NanoHTTPD(port) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val dataDir = File(baseDir.parentFile ?: baseDir, TataConstants.STATE_DIR_NAME)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method
        val body = HashMap<String, String>()
        if (method == Method.POST || method == Method.PUT) {
            try {
                session.parseBody(body)
            } catch (_: Exception) {
            }
        }

        return when {
            uri.startsWith("/tataplay/app/check_login.php") -> handleCheckLogin()
            uri.startsWith("/tataplay/app/send_otp.php") -> handleSendOtp(session)
            uri.startsWith("/tataplay/app/verify_otp.php") -> handleVerifyOtp(session)
            uri.startsWith("/tataplay/app/logout.php") -> handleLogout()
            uri.startsWith("/tataplay/playlist.php") -> handlePlaylist(session)
            uri.startsWith("/tataplay/get-mpd.php") -> handleGetMpd(session)
            uri == "/tataplay" || uri == "/tataplay/" || uri == "/tataplay/index.php" ->
                serveStaticFile(File(baseDir, "index.php"))
            uri.startsWith("/tataplay/") -> {
                val relative = uri.removePrefix("/tataplay/")
                serveStaticFile(File(baseDir, relative))
            }
            else -> newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun handleCheckLogin(): Response {
        val loginFile = File(dataDir, "login.json")
        val exists = loginFile.exists()
        val body = JSONObject(mapOf("exists" to exists)).toString()
        return jsonResponse(body)
    }

    private fun handleSendOtp(session: IHTTPSession): Response {
        val mobile = session.parms["mobile"].orEmpty().trim()
        if (!Regex("^[6-9]\\d{9}$").matches(mobile)) {
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid mobile number.")
        }

        dataDir.mkdirs()
        val credFile = File(dataDir, "guest-device.cred")
        if (!credFile.exists()) {
            val deviceId = generateNumericUuid()
            val guestRequest = Request.Builder()
                .url("https://tb.tapi.videoready.tv/binge-mobile-services/pub/api/v1/user/guest/register")
                .post(ByteArray(0).toRequestBody("text/plain".toMediaType()))
                .addHeader("accept", "application/json, text/plain, */*")
                .addHeader("authorization", "bearer undefined")
                .addHeader("content-length", "0")
                .addHeader("referer", "https://www.tataplaybinge.com/")
                .addHeader("deviceid", deviceId)
                .addHeader("origin", "https://www.tataplaybinge.com")
                .addHeader("user-agent", TataConstants.UA)
                .build()

            val guestResponse = client.newCall(guestRequest).execute().use { it.body?.string() }
            val guestData = try { JSONObject(guestResponse ?: "{}") } catch (_: Exception) { JSONObject() }
            val anonymousId = guestData.optJSONObject("data")?.optString("anonymousId", "").orEmpty()
            if (anonymousId.isBlank()) {
                return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to register device.")
            }
            val payload = JSONObject()
                .put("deviceId", deviceId)
                .put("anonymousId", anonymousId)
            credFile.writeText(payload.toString(2))
        }

        val cred = readJsonFile(credFile)
        val deviceId = cred?.optString("deviceId", "").orEmpty()
        val anonymousId = cred?.optString("anonymousId", "").orEmpty()
        if (deviceId.isBlank() || anonymousId.isBlank()) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Invalid device credentials.")
        }

        val otpRequest = Request.Builder()
            .url("https://tb.tapi.videoready.tv/binge-mobile-services/pub/api/v1/user/authentication/generateOTP")
            .post(ByteArray(0).toRequestBody("text/plain".toMediaType()))
            .addHeader("accept", "application/json, text/plain, */*")
            .addHeader("anonymousid", anonymousId)
            .addHeader("content-length", "0")
            .addHeader("deviceid", deviceId)
            .addHeader("mobilenumber", mobile)
            .addHeader("newotpflow", "4DOTP")
            .addHeader("origin", "https://www.tataplaybinge.com")
            .addHeader("platform", "BINGE_ANYWHERE")
            .addHeader("referer", "https://www.tataplaybinge.com/")
            .addHeader("user-agent", TataConstants.UA)
            .build()

        val responseText = client.newCall(otpRequest).execute().use { it.body?.string() }
        val data = try { JSONObject(responseText ?: "{}") } catch (_: Exception) { JSONObject() }
        return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, data.optString("message", "OTP send status unknown"))
    }

    private fun handleVerifyOtp(session: IHTTPSession): Response {
        val mobile = session.parms["mobile"].orEmpty().trim()
        val otp = session.parms["otp"].orEmpty().trim()
        val credFile = File(dataDir, "guest-device.cred")
        if (!credFile.exists()) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Missing device credentials.")
        }

        val cred = readJsonFile(credFile)
        val deviceId = cred?.optString("deviceId", "").orEmpty()
        val anonymousId = cred?.optString("anonymousId", "").orEmpty()
        if (deviceId.isBlank() || anonymousId.isBlank()) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Invalid device credentials.")
        }

        if (!Regex("^[6-9]\\d{9}$").matches(mobile) || !Regex("^\\d{4,6}$").matches(otp)) {
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid input.")
        }

        val validateBody = JSONObject()
            .put("mobileNumber", mobile)
            .put("otp", otp)
            .toString()
        val validateRequest = Request.Builder()
            .url("https://tb.tapi.videoready.tv/binge-mobile-services/pub/api/v1/user/authentication/validateOTP")
            .post(validateBody.toRequestBody("application/json".toMediaType()))
            .addHeader("accept", "application/json, text/plain, */*")
            .addHeader("anonymousid", anonymousId)
            .addHeader("content-type", "application/json")
            .addHeader("deviceid", deviceId)
            .addHeader("origin", "https://www.tataplaybinge.com")
            .addHeader("platform", "BINGE_ANYWHERE")
            .addHeader("referer", "https://www.tataplaybinge.com/")
            .addHeader("user-agent", TataConstants.UA)
            .build()

        val validateResponse = client.newCall(validateRequest).execute().use { it.body?.string() }
        val validateData = try { JSONObject(validateResponse ?: "{}") } catch (_: Exception) { JSONObject() }
        val validateInner = validateData.optJSONObject("data")
        val token = validateInner?.optString("userAuthenticateToken", "").orEmpty()
        val deviceToken = validateInner?.optString("deviceAuthenticateToken", "").orEmpty()
        if (token.isBlank()) {
            return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, validateData.optString("message", "OTP validation failed"))
        }

        val subRequest = Request.Builder()
            .url("https://tb.tapi.videoready.tv/binge-mobile-services/api/v4/subscriber/details")
            .get()
            .addHeader("accept", "application/json, text/plain, */*")
            .addHeader("anonymousid", anonymousId)
            .addHeader("authorization", "bearer $token")
            .addHeader("devicetype", "WEB")
            .addHeader("mobilenumber", mobile)
            .addHeader("origin", "https://www.tataplaybinge.com")
            .addHeader("platform", "BINGE_ANYWHERE")
            .addHeader("referer", "https://www.tataplaybinge.com/")
            .addHeader("user-agent", TataConstants.UA)
            .build()

        val subResponse = client.newCall(subRequest).execute().use { it.body?.string() }
        val subData = try { JSONObject(subResponse ?: "{}") } catch (_: Exception) { JSONObject() }
        val accountDetails = subData.optJSONObject("data")
            ?.optJSONArray("accountDetails")
            ?.optJSONObject(0)
        val dthStatus = accountDetails?.optString("dthStatus", "").orEmpty()

        val loginUrl: String
        val loginBody = JSONObject()
        when {
            dthStatus.isBlank() -> {
                loginUrl = "https://tb.tapi.videoready.tv/binge-mobile-services/api/v3/create/new/user"
                loginBody.put("dthStatus", "Non DTH User")
                loginBody.put("subscriberId", mobile)
                loginBody.put("login", "OTP")
                loginBody.put("mobileNumber", mobile)
                loginBody.put("isPastBingeUser", false)
                loginBody.put("eulaChecked", true)
                loginBody.put("packageId", "")
            }
            dthStatus == "DTH Without Binge" -> {
                loginUrl = "https://tb.tapi.videoready.tv/binge-mobile-services/api/v3/create/new/user"
                loginBody.put("dthStatus", "DTH Without Binge")
                loginBody.put("subscriberId", accountDetails?.optString("subscriberId", "") ?: "")
                loginBody.put("login", "OTP")
                loginBody.put("mobileNumber", mobile)
                loginBody.put("baId", JSONObject.NULL)
                loginBody.put("isPastBingeUser", false)
                loginBody.put("eulaChecked", true)
                loginBody.put("packageId", "")
                loginBody.put("referenceId", JSONObject.NULL)
            }
            else -> {
                loginUrl = "https://tb.tapi.videoready.tv/binge-mobile-services/api/v3/update/exist/user"
                loginBody.put("dthStatus", dthStatus)
                loginBody.put("subscriberId", accountDetails?.optString("subscriberId", "") ?: "")
                loginBody.put("bingeSubscriberId", accountDetails?.optString("bingeSubscriberId", "") ?: "")
                loginBody.put("baId", accountDetails?.optString("baId", "") ?: "")
                loginBody.put("login", "OTP")
                loginBody.put("mobileNumber", mobile)
                loginBody.put("payment_return_url", "https://www.tataplaybinge.com/subscription-transaction/status")
                loginBody.put("eulaChecked", true)
                loginBody.put("packageId", "")
            }
        }

        val loginRequest = Request.Builder()
            .url(loginUrl)
            .post(loginBody.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("accept", "application/json, text/plain, */*")
            .addHeader("anonymousid", anonymousId)
            .addHeader("authorization", "bearer $token")
            .addHeader("content-type", "application/json")
            .addHeader("device", "WEB")
            .addHeader("deviceid", deviceId)
            .addHeader("devicename", "Web")
            .addHeader("devicetoken", deviceToken)
            .addHeader("origin", "https://www.tataplaybinge.com")
            .addHeader("platform", "WEB")
            .addHeader("referer", "https://www.tataplaybinge.com/")
            .addHeader("user-agent", TataConstants.UA)
            .build()

        val loginResponse = client.newCall(loginRequest).execute().use { it.body?.string() }
        if (loginResponse.isNullOrBlank()) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Login failed")
        }
        File(dataDir, "login.json").writeText(loginResponse)
        val loginData = try { JSONObject(loginResponse) } catch (_: Exception) { JSONObject() }
        return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, loginData.optString("message", "Logged in"))
    }

    private fun handleLogout(): Response {
        val loginFile = File(dataDir, "login.json")
        val credFile = File(dataDir, "guest-device.cred")
        val cacheUrls = File(dataDir, "cache_urls.json")

        val loginData = readJsonFile(loginFile)
        val creds = readJsonFile(credFile)
        if (loginData == null || creds == null) {
            return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, "Already logged out.")
        }

        val loginInfo = loginData.optJSONObject("data") ?: return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, "Already logged out.")
        val baId = loginInfo.optString("baId", "")
        val subscriberId = loginInfo.optString("subscriberId", "")
        val subscriptionStatus = loginInfo.optString("subscriptionStatus", "")
        val dthStatus = loginInfo.optString("dthStatus", "")
        val userToken = loginInfo.optString("userAuthenticateToken", "")
        val deviceToken = loginInfo.optString("deviceAuthenticateToken", "")
        val deviceId = creds.optString("deviceId", "")

        val logoutUrl = "https://tb.tapi.videoready.tv/binge-mobile-services/api/v2/logout/$baId"
        val logoutRequest = Request.Builder()
            .url(logoutUrl)
            .post(ByteArray(0).toRequestBody("text/plain".toMediaType()))
            .addHeader("accept", "application/json, text/plain, */*")
            .addHeader("authorization", userToken)
            .addHeader("cache-control", "no-cache")
            .addHeader("content-length", "0")
            .addHeader("deviceid", deviceId)
            .addHeader("devicetoken", deviceToken)
            .addHeader("dthstatus", dthStatus)
            .addHeader("locale", "en")
            .addHeader("origin", "https://www.tataplaybinge.com")
            .addHeader("platform", "WEB")
            .addHeader("referer", "https://www.tataplaybinge.com/")
            .addHeader("subscriberid", subscriberId)
            .addHeader("subscriptiontype", subscriptionStatus)
            .addHeader("user-agent", TataConstants.UA)
            .build()

        val responseText = client.newCall(logoutRequest).execute().use { it.body?.string() }
        val responseData = try { JSONObject(responseText ?: "{}") } catch (_: Exception) { JSONObject() }
        val message = responseData.optString("message", "Already logged out.")
        if (message == "You have been successfully logged out.") {
            loginFile.delete()
            credFile.delete()
            cacheUrls.delete()
        }
        return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, message)
    }

    private fun handlePlaylist(session: IHTTPSession): Response {
        val loginFile = File(dataDir, "login.json")
        if (!loginFile.exists()) {
            return newFixedLengthResponse(Status.UNAUTHORIZED, MIME_PLAINTEXT, "Login required.")
        }

        var response = try {
            client.newCall(Request.Builder().url(TataConstants.ORIGIN_API).build())
                .execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
        } catch (_: Exception) {
            null
        }

        var channels = extractChannelArray(response)
        if (channels.length() == 0) {
            response = try {
                client.newCall(Request.Builder().url(TataConstants.FALLBACK_ORIGIN_API).build())
                    .execute()
                    .use { if (it.isSuccessful) it.body?.string() else null }
            } catch (_: Exception) {
                null
            }
            channels = extractChannelArray(response)
        }

        val skipIds = fetchSkipIds()
        val userAgent = session.headers["user-agent"].orEmpty()
        val liveHeaders = when {
            userAgent.contains("tivimate", ignoreCase = true) -> "|X-Forwarded-For=59.178.74.184 | Origin=https://watch.tataplay.com | Referer=https://watch.tataplay.com/"
            userAgent.contains("SparkleTV", ignoreCase = true) -> "|X-Forwarded-For=59.178.74.184|Origin=https://watch.tataplay.com|Referer=https://watch.tataplay.com/"
            else -> "|X-Forwarded-For=59.178.74.184&Origin=https://watch.tataplay.com&Referer=https://watch.tataplay.com/"
        }

        val host = session.headers["host"].orEmpty().ifBlank { "localhost:${TataConstants.DEFAULT_PORT}" }
        val baseUrl = "http://$host/tataplay"
        val streamPath = "get-mpd.php"

        val builder = StringBuilder()
        for (i in 0 until channels.length()) {
            val channel = channels.optJSONObject(i) ?: continue
            var channelId = channel.optInt("id", -1)
            if (channelId <= 0) {
                channelId = channel.optString("channel_id", "-1").toIntOrNull() ?: -1
            }

            if (channelId <= 0) continue
            if (skipIds.contains(channelId)) continue
            if (channel.optString("provider", "") == "DistroTV") continue

            val channelName = channel.optString("title", channel.optString("channel_name", ""))
            val channelLogo = channel.optString("transparentImageUrl", channel.optString("logo", ""))
            val genres = channel.optJSONArray("genres")
            val genreList = mutableListOf<String>()
            if (genres != null) {
                for (g in 0 until genres.length()) {
                    val genre = genres.optString(g)
                    if (genre != "HD") genreList.add(genre)
                }
            }
            val channelGenre = genreList.firstOrNull() ?: channel.optString("channel_genre", "General")

            val licenseUrl = "https://tp.drmlive-01.workers.dev?id=$channelId"
            val dashUrl = channel.optJSONObject("streamData")?.optString("dashWidewinePlayUrl", "").orEmpty()
            val proxyUrl = "$baseUrl/$streamPath?id=$channelId$liveHeaders"

            val channelLive = if (dashUrl.isNotBlank()) {
                val dashHost = try { URI(dashUrl).host } catch (_: Exception) { null }
                if (!dashHost.isNullOrBlank() && dashHost.startsWith("bpaita")) {
                    proxyUrl
                } else {
                    dashUrl
                }
            } else {
                proxyUrl
            }

            builder.append("#EXTINF:-1 tvg-id=\"ts$channelId\" tvg-logo=\"$channelLogo\" group-title=\"$channelGenre\",$channelName\n")
            builder.append("#KODIPROP:inputstream.adaptive.license_type=clearkey\n")
            builder.append("#KODIPROP:inputstream.adaptive.license_key=$licenseUrl\n")
            if (channelLive == proxyUrl) {
                builder.append("#KODIPROP:inputstream.adaptive.manifest_type=mpd\n")
            }
            builder.append("#EXTVLCOPT:http-user-agent=${TataConstants.UA}\n")
            builder.append("$channelLive\n\n")
        }

        val responseBody = builder.toString()
        return newFixedLengthResponse(Status.OK, "audio/x-mpegurl", "#EXTM3U\n$responseBody")
            .also { it.addHeader("Content-Disposition", "attachment; filename=\"playlist.m3u\"") }
    }

    private fun extractChannelArray(body: String?): JSONArray {
        val root = try {
            val text = body?.trim().orEmpty()
            if (text.startsWith("[")) {
                JSONArray(text)
            } else {
                JSONObject(text.ifBlank { "{}" })
            }
        } catch (_: Exception) {
            return JSONArray()
        }

        if (root is JSONArray) return root
        return findFirstJsonArray(root) ?: JSONArray()
    }

    private fun findFirstJsonArray(value: Any?): JSONArray? {
        return when (value) {
            is JSONArray -> value
            is JSONObject -> {
                listOf("channels", "data", "list", "items", "result", "results").forEach { key ->
                    findFirstJsonArray(value.opt(key))?.let { return it }
                }

                value.keys().forEach { key ->
                    findFirstJsonArray(value.opt(key))?.let { return it }
                }

                null
            }
            else -> null
        }
    }

    private fun handleGetMpd(session: IHTTPSession): Response {
        val rawId = session.parms["id"].orEmpty()
        val id = rawId.substringBefore("|").substringBefore("&")
        if (id.isBlank()) {
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing content ID.")
        }
        val loginFile = File(dataDir, "login.json")
        if (!loginFile.exists()) {
            return newFixedLengthResponse(Status.UNAUTHORIZED, MIME_PLAINTEXT, "Login required.")
        }

        val loginData = readJsonFile(loginFile)
        val loginInfo = loginData?.optJSONObject("data")
        val subscriberId = loginInfo?.optString("subscriberId", "").orEmpty()
        val userToken = loginInfo?.optString("userAuthenticateToken", "").orEmpty()
        if (subscriberId.isBlank() || userToken.isBlank()) {
            return newFixedLengthResponse(Status.FORBIDDEN, MIME_PLAINTEXT, "Invalid login data.")
        }

        val cacheFile = File(dataDir, "cache_urls.json")
        val cacheData = readJsonFile(cacheFile) ?: JSONObject()
        var mpdUrl = ""

        val credFile = File(dataDir, "guest-device.cred")
        val cred = readJsonFile(credFile)
        val deviceId = cred?.optString("deviceId", "").orEmpty()
        val anonymousId = cred?.optString("anonymousId", "").orEmpty()

        val cachedEntry = cacheData.optJSONObject(id)
        if (cachedEntry != null) {
            val cachedUrl = cachedEntry.optString("url", "")
            val exp = extractExpFromUrl(cachedUrl)
            if (exp != null && System.currentTimeMillis() / 1000 < exp) {
                mpdUrl = cachedUrl
            }
        }

        if (mpdUrl.isBlank()) {
            val apiId = id.removePrefix("ts")
            val contentUrl = TataConstants.CONTENT_API_PREFIX + apiId
            val contentRequest = Request.Builder()
                .url(contentUrl)
                .get()
                .addHeader("Authorization", "Bearer $userToken")
                .addHeader("subscriberId", subscriberId)
                .addHeader("deviceid", deviceId)
                .addHeader("anonymousid", anonymousId)
                .addHeader("platform", "BINGE_ANYWHERE")
                .addHeader("Origin", "https://www.tataplaybinge.com")
                .addHeader("Referer", "https://www.tataplaybinge.com/")
                .addHeader("User-Agent", TataConstants.UA)
                .build()
            Log.d("TataHttpServer", "Fetching manifest from: $contentUrl")
            val response = client.newCall(contentRequest).execute()
            val responseText = response.use { it.body?.string() }
            if (response.code != 200) {
                Log.e("TataHttpServer", "Content API error ${response.code}: $responseText")
            }
            val contentData = try { JSONObject(responseText ?: "{}") } catch (_: Exception) { JSONObject() }
            val dataObj = contentData.optJSONObject("data")
            val encryptedDash = dataObj?.optString("dashPlayreadyPlayUrl", "")
                ?.ifBlank { dataObj.optString("dashWidewinePlayUrl", "") }
                ?.ifBlank { dataObj.optString("dashPlayUrl", "") }

            if (encryptedDash.isNullOrBlank()) {
                Log.e("TataHttpServer", "Manifest URL not found for ID: $id. Response: $responseText")
                return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Manifest URL not found.")
            }

            val decryptedUrl = decryptUrl(encryptedDash, TataConstants.AES_KEY)
                ?.replace("bpaita", "bpaicatchupta")
                ?.replace("manifest", "Manifest")
            if (decryptedUrl.isNullOrBlank()) {
                return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to decrypt content URL.")
            }

            if (!decryptedUrl.contains("bpaicatchupta")) {
                val redirect = newFixedLengthResponse(Status.REDIRECT, MIME_PLAINTEXT, "")
                redirect.addHeader("Location", decryptedUrl)
                return redirect
            }

            val headerResp = fetchHeaders(decryptedUrl)
            val hdntl = extractHdntl(headerResp)
            val cleanUrl = decryptedUrl.substringBefore("?")
            mpdUrl = if (!hdntl.isNullOrBlank()) {
                if (hdntl.startsWith("hdntl=")) "$cleanUrl?$hdntl" else "$cleanUrl?hdntl=$hdntl"
            } else {
                val location = headerResp["Location"]
                if (!location.isNullOrBlank()) location.substringBefore("&") else decryptedUrl
            }

            cacheData.put(id, JSONObject().put("url", mpdUrl).put("updated_at", System.currentTimeMillis() / 1000))
            cacheFile.writeText(cacheData.toString(2))
        }

        val mpdContent = fetchMpd(mpdUrl)
            ?: return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to fetch MPD content.")

        val baseUrl = mpdUrl.substringBeforeLast("/")
        var processed = mpdContent.replace("dash/", "$baseUrl/dash/")

        val psshData = extractPsshData(mpdContent)
        if (psshData != null) {
            val kid = psshData.kid
            if (!kid.isNullOrBlank()) {
                processed = processed.replace(
                    "mp4protection:2011\"",
                    "mp4protection:2011\" cenc:default_KID=\"$kid\""
                )
            }
            if (!psshData.prPssh.isNullOrBlank()) {
                processed = processed.replace(
                    "\" value=\"PlayReady\"/>",
                    "\"><cenc:pssh>${psshData.prPssh}</cenc:pssh></ContentProtection>"
                )
            }
            if (!psshData.pssh.isNullOrBlank()) {
                processed = processed.replace(
                    "\" value=\"Widevine\"/>",
                    "\"><cenc:pssh>${psshData.pssh}</cenc:pssh></ContentProtection>"
                )
            }
        }

        val response = newFixedLengthResponse(Status.OK, "application/dash+xml", processed)
        response.addHeader("Content-Security-Policy", "default-src 'self';")
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("X-Frame-Options", "DENY")
        response.addHeader("X-XSS-Protection", "1; mode=block")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        response.addHeader("Content-Disposition", "attachment; filename=\"tp$id.mpd\"")
        return response
    }

    private fun fetchSkipIds(): Set<Int> {
        return try {
            val body = client.newCall(Request.Builder().url(TataConstants.STB_ONLY_API).build())
                .execute().use { it.body?.string() }
            val arr = JSONArray(body ?: "[]")
            buildSet {
                for (i in 0 until arr.length()) {
                    add(arr.optInt(i))
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun fetchHeaders(url: String): Map<String, String> {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", TataConstants.UA)
                .addHeader("Accept", "*/*")
                .addHeader("Connection", "close")
                .build()
            client.newCall(request).execute().use { response ->
                response.headers.names().associateWith { name -> response.header(name).orEmpty() }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun extractHdntl(headers: Map<String, String>): String? {
        val setCookie = headers["Set-Cookie"].orEmpty()
        if (setCookie.contains("hdntl=")) {
            val match = Regex("hdntl=([^;]+)").find(setCookie)
            if (match != null) return match.groupValues[1]
        }
        val hdntl = headers["hdntl"]
        return hdntl?.trim()
    }

    private fun fetchMpd(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", TataConstants.UA)
                .addHeader("Referer", "https://watch.tataplay.com/")
                .addHeader("Origin", "https://watch.tataplay.com")
                .build()
            client.newCall(request).execute().use { it.body?.string() }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractExpFromUrl(url: String): Long? {
        val query = url.substringAfter("?", "")
        if (query.contains("hdntl=")) {
            val hdntl = query.substringAfter("hdntl=")
            val exp = Regex("exp=(\\d+)").find(hdntl)?.groupValues?.get(1)
            return exp?.toLongOrNull()
        }
        val exp = Regex("exp=(\\d+)").find(query)?.groupValues?.get(1)
        return exp?.toLongOrNull()
    }

    private fun extractPsshData(mpd: String): PsshData? {
        val widevineMatch = Regex("(?is)schemeIdUri=\"[^\"]*edef8ba9[^\"]*\"[^>]*>.*?<cenc:pssh>(.*?)</cenc:pssh>").find(mpd)
        val playReadyMatch = Regex("(?is)schemeIdUri=\"[^\"]*9a04f079[^\"]*\"[^>]*>.*?<cenc:pssh>(.*?)</cenc:pssh>").find(mpd)
        val wvPssh = widevineMatch?.groupValues?.get(1)?.trim()
        val prPssh = playReadyMatch?.groupValues?.get(1)?.trim()

        if (wvPssh.isNullOrBlank() && prPssh.isNullOrBlank()) return null

        var kid = Regex("default_KID=\"([^\"]+)\"").find(mpd)?.groupValues?.get(1)
        if (kid.isNullOrBlank() && !wvPssh.isNullOrBlank()) {
            kid = resolveKidFromPssh(wvPssh)
        }
        return PsshData(pssh = wvPssh, prPssh = prPssh, kid = kid)
    }

    private fun resolveKidFromPssh(psshB64: String): String? {
        val cacheFile = File(dataDir, "cache_kid.json")
        val now = System.currentTimeMillis() / 1000
        val cache = readJsonFile(cacheFile) ?: JSONObject()

        val cacheKey = try {
            val raw = android.util.Base64.decode(psshB64, android.util.Base64.DEFAULT)
            raw.joinToString("") { String.format("%02x", it) }
        } catch (_: Exception) {
            null
        } ?: return null

        val cachedEntry = cache.optJSONObject(cacheKey)
        if (cachedEntry != null) {
            val ts = cachedEntry.optLong("timestamp", 0)
            if (now - ts < 600) {
                val kidHex = cachedEntry.optString("kid", "")
                return formatKid(kidHex)
            }
        }

        val payload = JSONObject().put("pssh", cacheKey).toString()
        val request = Request.Builder()
            .url(TataConstants.SECURE_KID_API)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute().use { it.body?.string() }
        val json = try { JSONObject(response ?: "{}") } catch (_: Exception) { JSONObject() }
        val encryptedKid = json.optString("encryptedKID", "")
        if (encryptedKid.isBlank()) return null

        cache.put(cacheKey, JSONObject().put("kid", encryptedKid).put("timestamp", now))
        cacheFile.writeText(cache.toString(2))
        return formatKid(encryptedKid)
    }

    private fun formatKid(kidHex: String): String? {
        if (kidHex.length < 32) return null
        return "${kidHex.substring(0, 8)}-${kidHex.substring(8, 12)}-${kidHex.substring(12, 16)}-${kidHex.substring(16, 20)}-${kidHex.substring(20)}"
    }

    private fun decryptUrl(encryptedUrl: String, key: String): String? {
        return try {
            val clean = encryptedUrl.substringBefore("#")
            val decoded = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")

            val keyBytes = ByteArray(16)
            val rawKey = key.toByteArray()
            System.arraycopy(rawKey, 0, keyBytes, 0, rawKey.size.coerceAtMost(16))

            val secretKey = SecretKeySpec(keyBytes, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            String(cipher.doFinal(decoded))
        } catch (_: Exception) {
            null
        }
    }

    private fun readJsonFile(file: File): JSONObject? {
        return try {
            if (!file.exists()) return null
            JSONObject(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonResponse(body: String): Response {
        val response = newFixedLengthResponse(Status.OK, "application/json", body)
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun serveStaticFile(file: File): Response {
        if (!file.exists() || !file.isFile) {
            return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        val mime = mimeTypeFor(file.name)
        val fis = FileInputStream(file)
        val response = newChunkedResponse(Status.OK, mime, fis)
        response.addHeader("Cache-Control", "no-cache")
        return response
    }

    private fun mimeTypeFor(name: String): String {
        return when {
            name.endsWith(".html", true) || name.endsWith(".php", true) -> "text/html"
            name.endsWith(".js", true) -> "application/javascript"
            name.endsWith(".css", true) -> "text/css"
            name.endsWith(".json", true) -> "application/json"
            name.endsWith(".png", true) -> "image/png"
            name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
            name.endsWith(".svg", true) -> "image/svg+xml"
            else -> "application/octet-stream"
        }
    }

    private fun generateNumericUuid(): String {
        val left = (100..999).random()
        val right = (10..99).random()
        return "${left}${System.currentTimeMillis()}${right}"
    }

    private data class PsshData(
        val pssh: String?,
        val prPssh: String?,
        val kid: String?
    )
}
