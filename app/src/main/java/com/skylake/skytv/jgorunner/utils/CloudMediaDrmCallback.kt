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

        val builder = Request.Builder()
            .url(licenseUrl)
            .post(request.data.toRequestBody("application/octet-stream".toMediaType()))

        // Prioritize our custom headers
        headers.forEach { (k, v) ->
            builder.header(k, v)
        }

        // Ensure some basics if missing
        if (!headers.containsKey("Content-Type")) {
            builder.header("Content-Type", "application/octet-stream")
        }

        val okRequest = builder.build()
        LogCollector.log("DRM Key Request: POST $licenseUrl")

        return httpClient.newCall(okRequest).execute().use { response ->
            val responseBodyBytes = response.body?.bytes() ?: throw Exception("Empty license response")

            val hexPreview = if (responseBodyBytes.size >= 8) {
                responseBodyBytes.take(8).joinToString("") { "%02x".format(it) }
            } else "too-short"

            if (!response.isSuccessful) {
                val errBody = String(responseBodyBytes.take(1024).toByteArray()).filter { it.code in 32..126 }
                LogCollector.log("DRM Error ${response.code}: $errBody")
                throw Exception("License server error: ${response.code}")
            }

            LogCollector.log("DRM Resp size=${responseBodyBytes.size}, hex8=$hexPreview")

            // If it looks like JSON or Text (starts with { or < or is very short), log it
            if (responseBodyBytes.size < 1000) {
                val bodyText = String(responseBodyBytes).filter { it.code in 32..126 || it == '\n' }
                if (bodyText.contains("{") || bodyText.contains("<") || bodyText.contains("error")) {
                    LogCollector.log("DRM Potential Error Body: $bodyText")
                }
            }

            responseBodyBytes
        }
    }
}
