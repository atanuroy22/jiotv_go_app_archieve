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

        val isAlex = licenseUrl.contains("alex4528.site", true)

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

        if (isAlex) {
            builder.header("Origin", "https://alex4528.site")
            builder.header("Referer", "https://alex4528.site/")
            builder.header("Sec-Fetch-Mode", "cors")
            builder.header("Sec-Fetch-Site", "cross-site")
            builder.header("Sec-Fetch-Dest", "empty")
            builder.header("Accept", "*/*")
            builder.header("Accept-Language", "en-US,en;q=0.9")
        }

        if (!headers.containsKey("Content-Type")) {
            builder.header("Content-Type", contentType)
        }

        val okRequest = builder.build()
        LogCollector.log("DRM Key Request: POST $licenseUrl (Type: $contentType)")

        var retryCount = 0
        while (retryCount < 5) {
            try {
                val call = httpClient.newCall(okRequest)
                val response = call.execute()
                response.use { resp ->
                    val responseBodyBytes = resp.body?.bytes() ?: throw Exception("Empty license response")

                    // Retry on 502/504/500 AND 403 (alex server sometimes throws 403 on temporary load or header mismatch)
                    if (resp.code == 502 || resp.code == 504 || resp.code == 500 || (isAlex && resp.code == 403)) {
                        LogCollector.log("DRM Server Error ${resp.code}, retrying (${retryCount + 1}/5)...")
                        retryCount++
                        Thread.sleep(1500)
                        // Explicitly continue the while loop
                    } else if (!resp.isSuccessful) {
                        val errBody = String(responseBodyBytes.take(1024).toByteArray()).filter { it.code in 32..126 }
                        LogCollector.log("DRM Error ${resp.code}: $errBody")
                        throw Exception("License server error: ${resp.code}")
                    } else {
                        return responseBodyBytes
                    }
                }
            } catch (e: Exception) {
                if (e is java.io.IOException || e is java.net.SocketTimeoutException) {
                   LogCollector.log("DRM Network Error: ${e.message}, retrying (${retryCount + 1}/5)...")
                } else {
                   LogCollector.log("DRM Exception: ${e.message}")
                   if (retryCount >= 4) throw e
                }
                retryCount++
                Thread.sleep(1500)
            }
        }
        throw Exception("DRM Key Request failed after maximum retries")
    }
}
