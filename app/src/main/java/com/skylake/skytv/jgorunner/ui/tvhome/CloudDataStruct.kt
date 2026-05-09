package com.skylake.skytv.jgorunner.ui.tvhome

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class CloudServer(
    @SerializedName("name") val name: String,
    @SerializedName("url") val url: String,
    @SerializedName("logo") val logo: String
)

@Keep
data class CloudChannel(
    @SerializedName("type") val type: String?,
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String,
    @SerializedName("group") val group: String?,
    @SerializedName("language") val language: String?,
    @SerializedName("logo") val logo: String?,
    @SerializedName("user_agent") val userAgent: String?,
    @SerializedName("mpd_url") val mpdUrl: String?,
    @SerializedName("m3u8_url") val m3u8Url: String?,
    @SerializedName("license_url") val licenseUrl: String?,
    @SerializedName("headers") val headers: Map<String, String>?,
    @SerializedName("expires_in") val expiresIn: String?
)
