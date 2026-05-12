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

        if (!headers.containsKey("Content-Type")) {
            builder.header("Content-Type", contentType)
        }

        val okRequest = builder.build()
        LogCollector.log("DRM Key Request: POST $licenseUrl (Type: $contentType)")

        var retryCount = 0
        while (retryCount < 5) {
            try {
                httpClient.newCall(okRequest).execute().use { response ->
                    val responseBodyBytes = response.body?.bytes() ?: throw Exception("Empty license response")

                    if (response.code == 502 || response.code == 504 || response.code == 500) {
                        LogCollector.log("DRM Server Temporary Error ${response.code}, retrying ($retryCount/5)...")
                        retryCount++
                        Thread.sleep(1000)
                        return@use // continue loop
                    }

                    if (!response.isSuccessful) {
                        val errBody = String(responseBodyBytes.take(1024).toByteArray()).filter { it.code in 32..126 }
                        LogCollector.log("DRM Error ${response.code}: $errBody")
                        throw Exception("License server error: ${response.code}")
                    }

                    return responseBodyBytes
                }
            } catch (e: Exception) {
                if (retryCount >= 4) throw e
                retryCount++
                Thread.sleep(1000)
            }
        }
        throw Exception("DRM Key Request failed after retries")
    }
}
