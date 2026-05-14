package com.skylake.skytv.jgorunner.utils

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest
import androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

@UnstableApi
class CloudMediaDrmCallback(
    private val defaultLicenseUrl: String,
    private val headers: Map<String, String>,
    private val httpClient: OkHttpClient
) : MediaDrmCallback {

    override fun executeProvisionRequest(uuid: UUID, request: ProvisionRequest): ByteArray {
        val url = request.defaultUrl
        val okRequest = Request.Builder()
            .url(url)
            .post(request.data.toRequestBody("application/octet-stream".toMediaType()))
            .build()

        LogCollector.log("DRM Provision Request: $url")
        return httpClient.newCall(okRequest).execute().use { response ->
            if (!response.isSuccessful) {
                LogCollector.log("DRM Provision Error ${response.code}")
                throw Exception("Provisioning failed: ${response.code}")
            }
            response.body?.bytes() ?: throw Exception("Empty provisioning response")
        }
    }

    override fun executeKeyRequest(uuid: UUID, request: KeyRequest): ByteArray {
        var licenseUrl = request.licenseServerUrl
        if (licenseUrl.isNullOrEmpty() || licenseUrl.contains("provisioning.widevine.com")) {
            licenseUrl = defaultLicenseUrl
        }

        val urlLower = licenseUrl.lowercase()
        val isAlex = urlLower.contains("alex4528.site")
        val isWebPlay = urlLower.contains("webplay.fun")

        val requestData = request.data
        val contentType = if (requestData.isNotEmpty() && requestData[0].toInt().toChar() == '{') {
            "application/json"
        } else {
            "application/octet-stream"
        }

        val builder = Request.Builder()
            .url(licenseUrl)
            .post(requestData.toRequestBody(contentType.toMediaType()))

        headers.forEach { (k, v) ->
            builder.header(k, v)
        }

        if (isAlex || isWebPlay) {
            builder.header("Origin", if (isAlex) "https://alex4528.site" else "https://temp.webplay.fun")
            builder.header("Referer", if (isAlex) "https://alex4528.site/" else "https://temp.webplay.fun/")
            builder.header("Sec-Fetch-Mode", "cors")
            builder.header("Sec-Fetch-Site", if (isAlex) "same-origin" else "cross-site")
            builder.header("Sec-Fetch-Dest", "empty")
            builder.header("Accept", "*/*")
            builder.header("User-Agent", "plaYtv/7.1.3 (Linux;Android 14)")
        }

        if (!headers.containsKey("Content-Type")) {
            builder.header("Content-Type", contentType)
        }

        val okRequest = builder.build()
        val headerLog = okRequest.headers.names().joinToString { name -> "$name: ${okRequest.headers[name]}" }
        LogCollector.log("DRM Key Request: POST $licenseUrl (Type: $contentType) Headers: [$headerLog]")

        var retryCount = 0
        val maxRetries = 5
        while (retryCount <= maxRetries) {
            try {
                val currentCall = httpClient.newCall(okRequest)
                currentCall.execute().use { response ->
                    val bodyBytes = response.body?.bytes() ?: throw Exception("Empty license response")

                    if (response.isSuccessful) {
                        return bodyBytes
                    }

                    // Retry on common server errors or 403 (picky proxies)
                    if (response.code == 502 || response.code == 504 || response.code == 500 || response.code == 503 || response.code == 403 || response.code == 429) {
                        if (retryCount < maxRetries) {
                            LogCollector.log("DRM Server Error ${response.code} for $licenseUrl, retrying (${retryCount + 1}/$maxRetries)...")
                            retryCount++
                            Thread.sleep(1000L + (retryCount * 1000L)) // Increased delay
                            return@use // This will cause the while loop to continue
                        }
                    }

                    if (!response.isSuccessful) {
                        val errBody = String(bodyBytes.take(1024).toByteArray()).filter { it.code in 32..126 }
                        LogCollector.log("DRM Error ${response.code}: $errBody")
                        throw Exception("License server error: ${response.code}")
                    }

                    return bodyBytes
                }
            } catch (e: Exception) {
                if (retryCount < maxRetries && (e is java.io.IOException || e is java.net.SocketTimeoutException)) {
                    LogCollector.log("DRM Network Error: ${e.message}, retrying (${retryCount + 1}/$maxRetries)...")
                    retryCount++
                    Thread.sleep(1500)
                } else {
                    LogCollector.log("DRM Exception: ${e.message}")
                    throw e
                }
            }
        }
        throw Exception("DRM Key Request failed after maximum retries")
    }
}
