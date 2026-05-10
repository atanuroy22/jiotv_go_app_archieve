package com.skylake.skytv.jgorunner.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

class SkySharedPref(context: Context) {
    companion object {
        private const val PREF_NAME = "SkySharedPref"

        @Volatile
        private var instance: SkySharedPref? = null

        @JvmStatic
        // Singleton instance
        fun getInstance(context: Context): SkySharedPref {
            return instance ?: synchronized(this) {
                instance ?: SkySharedPref(context.applicationContext).also { instance = it }
            }
        }
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val editor: SharedPreferences.Editor = sharedPreferences.edit()

    var myPrefs = readFromSharedPreferences() // This will be updated with saved preferences

    // Save all preferences
    fun savePreferences() {
        SharedPrefStructure::class.memberProperties.forEach { property ->
            val annotation = property.findAnnotation<SharedPrefKey>()
            if (annotation != null) {
                // Make the property accessible (in case it's private)
                property.isAccessible = true

                // Get the value of the property
                val value = myPrefs.let {
                    property.get(it)
                }

                // If the value is null, skip saving
                if (value != null) {
                    when (value) {
                        is String -> editor.putString(annotation.key, value)
                        is Boolean -> editor.putBoolean(annotation.key, value)
                        is Int -> editor.putInt(annotation.key, value)
                        is Float -> editor.putFloat(annotation.key, value)
                        is Long -> editor.putLong(annotation.key, value)
                        else -> {
                            Log.e("SkySharedPref", "Unsupported type: ${value::class.java}")
                            // Log the unsupported type and skip it
                        }
                    }
                }
            }
        }
        editor.apply() // Apply changes asynchronously
    }

    // Save all preferences
    fun savePreferencesQuick() {
        SharedPrefStructure::class.memberProperties.forEach { property ->
            val annotation = property.findAnnotation<SharedPrefKey>()
            if (annotation != null) {
                // Make the property accessible (in case it's private)
                property.isAccessible = true

                // Get the value of the property
                val value = myPrefs.let {
                    property.get(it)
                }

                // If the value is null, skip saving
                if (value != null) {
                    when (value) {
                        is String -> editor.putString(annotation.key, value)
                        is Boolean -> editor.putBoolean(annotation.key, value)
                        is Int -> editor.putInt(annotation.key, value)
                        is Float -> editor.putFloat(annotation.key, value)
                        is Long -> editor.putLong(annotation.key, value)
                        else -> {
                            Log.e("SkySharedPref", "Unsupported type: ${value::class.java}")
                            // Log the unsupported type and skip it
                        }
                    }
                }
            }
        }
        editor.commit()  // Apply changes synchronously
    }


    // Read data from SharedPreferences using annotated keys
    private fun readFromSharedPreferences(): SharedPrefStructure {
        val instance = SharedPrefStructure()

        SharedPrefStructure::class.memberProperties.forEach { property ->
            val annotation = property.findAnnotation<SharedPrefKey>()
            annotation?.let { ann ->
                property.isAccessible = true

                val defaultValue = try {
                    property.get(instance)
                } catch (_: Exception) {
                    null
                }

                // Retrieve value from SharedPreferences
                val value = when (property.returnType.classifier) {
                    String::class -> sharedPreferences.getString(ann.key, defaultValue as? String)
                    Boolean::class -> sharedPreferences.getBoolean(ann.key, (defaultValue as? Boolean) ?: false)
                    Int::class -> sharedPreferences.getInt(ann.key, (defaultValue as? Int) ?: 0)
                    Float::class -> sharedPreferences.getFloat(ann.key, (defaultValue as? Float) ?: 0f)
                    Long::class -> sharedPreferences.getLong(ann.key, (defaultValue as? Long) ?: 0L)
                    else -> {
                        Log.w("SkySharedPref", "Unsupported type for key: ${ann.key}")
                        return@let // Skip unsupported types
                    }
                }

                // Set the value to the property
                if (property is KMutableProperty<*>) {
                    try {
                        property.setter.call(instance, value)
                    } catch (e: Exception) {
                        Log.e(
                            "SkySharedPref",
                            "Error setting value for ${property.name}: ${e.message}"
                        )
                    }
                }
            }
        }
        return instance
    }

    // Clear all preferences
    fun clearPreferences() {
        editor.clear().apply()
        myPrefs = SharedPrefStructure() // Reset preferences after clearing
    }

    // Data class to store shared preferences keys and values
    data class SharedPrefStructure(
        @SharedPrefKey("serve_local") var serveLocal: Boolean = false,
        @SharedPrefKey("auto_start_server") var autoStartServer: Boolean = true,
        @SharedPrefKey("auto_start_on_boot") var autoStartOnBoot: Boolean = false,
        @SharedPrefKey("auto_start_on_boot_foreground") var autoStartOnBootForeground: Boolean = false,
        @SharedPrefKey("auto_start_iptv") var autoStartIPTV: Boolean = false,
        @SharedPrefKey("enable_auto_update") var enableAutoUpdate: Boolean = true,
        @SharedPrefKey("jtv_go_port") var jtvGoServerPort: Int = 5350,
        @SharedPrefKey("jtv_go_binary_name") var jtvGoBinaryName: String? = null,
        @SharedPrefKey("jtv_binary_version") var jtvGoBinaryVersion: String? = "v0.0.0",
        @SharedPrefKey("jtv_config_location") var jtvConfigLocation: String? = null,
        @SharedPrefKey("iptv_app_name") var iptvAppName: String? = null,
        @SharedPrefKey("iptv_app_package_name") var iptvAppPackageName: String? = null,
        @SharedPrefKey("iptv_app_launch_activity") var iptvAppLaunchActivity: String? = null,
        @SharedPrefKey("iptv_launch_countdown") var iptvLaunchCountdown: Int = 4,
        @SharedPrefKey("recent_channels_json") var recentChannelsJson: String? = null,
        @SharedPrefKey("overlayPermissionAttempts") var overlayPermissionAttempts: Int = 0,
        @SharedPrefKey("filterQ") var filterQ: String? = null,
        @SharedPrefKey("filterL") var filterL: String? = null,
        @SharedPrefKey("filterC") var filterC: String? = null,
        @SharedPrefKey("filterQX") var filterQX: String? = null,
        @SharedPrefKey("filterLX") var filterLX: String? = null,
        @SharedPrefKey("filterCX") var filterCX: String? = null,
        @SharedPrefKey("filterLI") var filterLI: String? = "",
        @SharedPrefKey("filterLI2") var filterLI2: String? = "",
        @SharedPrefKey("filterCI") var filterCI: String? = "",
        @SharedPrefKey("filterCI2") var filterCI2: String? = "",
        @SharedPrefKey("login_chk") var loginChk: Boolean = true,
        @SharedPrefKey("cast_channel_name") var castChannelName: String? = "",
        @SharedPrefKey("cast_channel_logo") var castChannelLogo: String? = "",
        @SharedPrefKey("lastFetchTime") var lastFetchTime: Int? = 0,
        @SharedPrefKey("currentPort") var currentPort: Int = 0,
        @SharedPrefKey("recentChannels") var recentChannels: String? = "",
        @SharedPrefKey("operationMODE") var operationMODE: Int = 999,
        @SharedPrefKey("darkMODE") var darkMODE: Boolean = false,
        @SharedPrefKey("selectedScreenTV") var selectedScreenTV: String? = "0",
        @SharedPrefKey("selectedZoneTabTV") var selectedZoneTabTV: Int = 0,
        @SharedPrefKey("selectedRemoteNavTV") var selectedRemoteNavTV: String? = "0",
        @SharedPrefKey("custURL") var custURL: String? = "https://atanuroy22.github.io/iptv/output/all.m3u",
        @SharedPrefKey("channelListJson") var channelListJson: String? = "",
        @SharedPrefKey("expDebug") var expDebug: Boolean = false,
        @SharedPrefKey("last_selected_category_exp") var lastSelectedCategoryExp: String? = "All",
        @SharedPrefKey("showPLAYLIST") var showPLAYLIST: Boolean = true,
        @SharedPrefKey("enable_custom_channels") var enableCustomChannels: Boolean = false,
        @SharedPrefKey("showRecentTab") var showRecentTab: Boolean = false,
        @SharedPrefKey("startTvAutomatically") var startTvAutomatically: Boolean = false,
        @SharedPrefKey("startTvAutoDelay") var startTvAutoDelay: Boolean = false,
        @SharedPrefKey("startTvAutoDelayTime") var startTvAutoDelayTime: Int = 2,
        @SharedPrefKey("currChannelName") var currChannelName: String? = "",
        @SharedPrefKey("currChannelLogo") var currChannelLogo: String? = "",
        @SharedPrefKey("currChannelUrl") var currChannelUrl: String? = "",
        @SharedPrefKey("unitHolder") var unitHolder: Int = 0,
        @SharedPrefKey("customPlaylistSupport") var customPlaylistSupport: Boolean = true,
        @SharedPrefKey("home_iptv_enabled") var homeIptvEnabled: Boolean = true,
        @SharedPrefKey("genericTvIcon") var genericTvIcon: Boolean = false,
        @SharedPrefKey("preRelease") var preRelease: Boolean = false,
        @SharedPrefKey("epgDebug") var epgDebug: Boolean = false,
        @SharedPrefKey("lastSelectedCategoriesExp") var lastSelectedCategoriesExp: String? = "",
        @SharedPrefKey("lastSelectedLanguagesExp") var lastSelectedLanguagesExp: String? = "",
        @SharedPrefKey("lastSelectedCountriesExp") var lastSelectedCountriesExp: String? = "",
        @SharedPrefKey("setupPending") var setupPending: Boolean = true,
        @SharedPrefKey("enable_pip") var enablePip: Boolean = false,

        // Widget-specific preferences
        @SharedPrefKey("widget_show_logs") var widgetShowLogs: Boolean = false,
        @SharedPrefKey("widget_logs") var widgetLogs: String? = "",

        // Binary fetch cache
        @SharedPrefKey("lastFetchTimeRelease") var lastFetchTimeRelease: Long = 0L,
        @SharedPrefKey("cachedReleaseName") var cachedReleaseName: String? = null,
        @SharedPrefKey("cachedReleaseVersion") var cachedReleaseVersion: String? = null,
        @SharedPrefKey("cachedReleaseUrl") var cachedReleaseUrl: String? = null,
        @SharedPrefKey("cachedReleaseSize") var cachedReleaseSize: Long = 0L,

        // Cloud UI Preferences
        @SharedPrefKey("cloud_autoplay_enabled") var cloudAutoplayEnabled: Boolean = true,
        @SharedPrefKey("last_cloud_server_url") var lastCloudServerUrl: String? = null,
        @SharedPrefKey("cloud_category_filter") var cloudCategoryFilter: String? = null,
        @SharedPrefKey("cloud_ui_scale") var cloudUiScale: Float = 1.0f,
        @SharedPrefKey("cloud_animation_enabled") var cloudAnimationEnabled: Boolean = true,
        @SharedPrefKey("cloud_focus_animation_enabled") var cloudFocusAnimationEnabled: Boolean = true,
        @SharedPrefKey("cloud_autoplay_first_channel") var cloudAutoplayFirstChannel: Boolean = false,
        @SharedPrefKey("cloud_autoplay_last_channel") var cloudAutoplayLastChannel: Boolean = false,
        @SharedPrefKey("last_cloud_played_channel_id") var lastCloudPlayedChannelId: String? = null,
        @SharedPrefKey("last_cloud_server_name") var lastCloudServerName: String? = null,

        )


    // Annotation class to define the key for SharedPreferences
    @Target(AnnotationTarget.PROPERTY)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class SharedPrefKey(val key: String)
}
