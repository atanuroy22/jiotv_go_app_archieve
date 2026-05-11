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
        val url = "${request.defaultUrl}&signedRequest=${String(request.data)}"
        val okRequest = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody())
            .build()

        return httpClient.newCall(okRequest).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Provisioning failed: ${response.code}")
            response.body?.bytes() ?: throw Exception("Empty provisioning response")
        }
    }

    override fun executeKeyRequest(uuid: UUID, request: KeyRequest): ByteArray {
        var licenseUrl = request.licenseServerUrl
        if (licenseUrl.isNullOrEmpty()) {
            licenseUrl = defaultLicenseUrl
        }

        val builder = Request.Builder()
            .url(licenseUrl)
            .post(request.data.toRequestBody("application/octet-stream".toMediaType()))

        headers.forEach { (k, v) ->
            builder.header(k, v)
        }

        val okRequest = builder.build()
        LogCollector.log("DRM Request: POST $licenseUrl")
        LogCollector.log("DRM Request Headers: ${okRequest.headers.names().joinToString(", ")}")

        return httpClient.newCall(okRequest).execute().use { response ->
            val responseBodyBytes = response.body?.bytes() ?: throw Exception("Empty license response")

            val bodyString = if (responseBodyBytes.isNotEmpty()) {
                val preview = responseBodyBytes.take(512).toByteArray()
                String(preview).filter { it.isLetterOrDigit() || it.isWhitespace() || it == '{' || it == '}' || it == '"' || it == ':' || it == ',' }
            } else "null"

            if (!response.isSuccessful) {
                LogCollector.log("DRM Error ${response.code}: $bodyString")
                throw Exception("License server error: ${response.code}")
            }

            if (responseBodyBytes.size < 1000) {
                LogCollector.log("DRM Response Content (${responseBodyBytes.size} bytes): $bodyString")
            } else {
                LogCollector.log("DRM Success: ${responseBodyBytes.size} bytes")
            }
            responseBodyBytes
        }
    }
}
