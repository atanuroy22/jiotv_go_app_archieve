package com.skylake.skytv.jgorunner.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.skylake.skytv.jgorunner.activities.MainActivity
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.ui.components.MultiSelectFilterDialog
import com.skylake.skytv.jgorunner.ui.tvhome.CloudChannel
import com.skylake.skytv.jgorunner.utils.LogCollector
import com.skylake.skytv.jgorunner.utils.normalizePlaybackUrl
import com.skylake.skytv.jgorunner.utils.setupCustomPlaybackLogic
import com.skylake.skytv.jgorunner.utils.cleanupPlaybackLogic
import com.skylake.skytv.jgorunner.data.CloudDataManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Calendar
import java.util.concurrent.TimeUnit

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(UnstableApi::class)
@Composable
fun CloudPlayerScreen(
    preferenceManager: SkySharedPref,
    cloudChannelList: List<CloudChannel>,
    initialIndex: Int,
    serverUrl: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    val activeList = remember { CloudDataManager.currentChannelList ?: cloudChannelList }

    var currentIndex by remember(initialIndex) { mutableIntStateOf(initialIndex) }
    var activeCloudChannel by remember(currentIndex) {
        mutableStateOf(activeList.getOrNull(currentIndex))
    }

    val rootFocusRequester = remember { FocusRequester() }
    val overlayFocusRequester = remember { FocusRequester() }
    val sidePanelFocusRequester = remember { FocusRequester() }
    val settingsPanelFocusRequester = remember { FocusRequester() }

    var showChannelPanel by remember { mutableStateOf(false) }
    var showSettingsPanel by remember { mutableStateOf(false) }
    var panelSelectedIndex by remember { mutableIntStateOf(currentIndex) }
    var showChannelOverlay by remember { mutableStateOf(false) }
    var overlayVisibilityTick by remember { mutableLongStateOf(0L) }
    var playerError by remember { mutableStateOf<String?>(null) }
    val retryCountRef = remember { mutableIntStateOf(0) }
    var exoPlayerView: PlayerView? by remember { mutableStateOf(null) }
    var numericBuffer by remember { mutableStateOf("") }
    var showNumericOverlay by remember { mutableStateOf(false) }
    var numericJob by remember { mutableStateOf<Job?>(null) }
    var lastAttemptWasDash by remember(currentIndex) { mutableStateOf(false) }

    var isFallbackAttempt by remember(currentIndex) { mutableStateOf(false) }
    var isSilentTransition by remember(currentIndex) { mutableStateOf(false) }

    var currentResizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    val okHttpClient = remember {
        val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "0123456789abcdef"

        OkHttpClient.Builder()
            .connectTimeout(35, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                val builder = request.newBuilder()

                val jioUA = "JioTV/7.0.8 (Linux; Android 13; Pixel 7 Pro Build/TQ1A.221205.011; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/110.0.5481.64 Mobile Safari/537.36"

                if (url.contains("jio.com", true) || url.contains("webplay.fun", true)) {
                    builder.header("User-Agent", jioUA)
                    builder.header("os", "android")
                    builder.header("devicetype", "phone")
                    builder.header("uniqueId", androidId)
                    builder.header("deviceId", androidId)
                    builder.header("appname", "com.jio.jiotv")
                    builder.header("versionCode", "323")
                    builder.header("X-Jio-Network-Type", "WIFI")
                    builder.header("X-Requested-With", "com.jio.jiotv")
                    builder.header("Origin", "https://www.jio.com")
                    builder.header("Referer", "https://www.jio.com/")
                }

                if (url.contains("alex4528.site", true)) {
                    builder.header("Origin", "https://alex4528.site")
                    builder.header("Referer", "https://alex4528.site/")
                    builder.header("Sec-Fetch-Mode", "cors")
                    builder.header("Sec-Fetch-Site", "same-origin")
                    builder.header("Sec-Fetch-Dest", "empty")
                }

                chain.proceed(builder.build())
            }
            .build()
    }

    val trackSelector = remember { DefaultTrackSelector(context) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        LogCollector.logError("CloudPlayer Error: ${error.errorCodeName} - ${error.message}", error)

                        val isDrmError = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
                            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
                            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
                            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
                            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
                            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
                            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR -> true
                            else -> false
                        }
                        val isFallbackCandidateError =
                            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                                error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                                isDrmError

                        if (!isFallbackAttempt && lastAttemptWasDash && !activeCloudChannel?.m3u8Url.isNullOrBlank() &&
                            isFallbackCandidateError) {

                            LogCollector.log("Primary stream error, trying alternate URL for ${activeCloudChannel?.name}")
                            isFallbackAttempt = true
                            isSilentTransition = true
                            // Stop the player immediately to cancel any internal retries
                            stop()
                            clearMediaItems()
                            return
                        }

                        if (!isSilentTransition) {
                            playerError = "${error.errorCodeName}\n${error.message}"
                        }

                        // Only auto-retry if NOT attempting a fallback and we haven't reached max retries
                        if (!isSilentTransition && retryCountRef.value < 5) {
                            retryCountRef.value++
                            LogCollector.log("Auto-retrying playback ($retryCountRef/5)...")
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (playerError != null) {
                                    prepare()
                                    play()
                                }
                            }, 3000)
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            retryCountRef.value = 0
                            playerError = null
                            isSilentTransition = false

                            activeCloudChannel?.let { ch ->
                                if (!ch.id.isNullOrBlank() && serverUrl != null) {
                                    val mapJson = preferenceManager.myPrefs.lastCloudPlayedChannelId ?: "{}"
                                    val map: MutableMap<String, String> = try {
                                        gson.fromJson(mapJson, object : TypeToken<MutableMap<String, String>>() {}.type) ?: mutableMapOf()
                                    } catch (_: Exception) { mutableMapOf() }

                                    map[serverUrl] = ch.id
                                    preferenceManager.myPrefs.lastCloudPlayedChannelId = gson.toJson(map)
                                    preferenceManager.savePreferences()
                                }
                            }
                        }
                    }
                })
            }
    }

    LaunchedEffect(preferenceManager.myPrefs.cloudQualityMaxHeight) {
        val maxHeight = preferenceManager.myPrefs.cloudQualityMaxHeight
        val resolvedHeight = if (maxHeight <= 0) Int.MAX_VALUE else maxHeight
        trackSelector.setParameters(
            trackSelector.buildUponParameters().setMaxVideoSize(Int.MAX_VALUE, resolvedHeight)
        )
    }

    LaunchedEffect(currentIndex, isFallbackAttempt, preferenceManager.myPrefs.cloudQualityMaxHeight) {
        val ch = activeList.getOrNull(currentIndex)
        if (ch == null) return@LaunchedEffect

        activeCloudChannel = ch

        val normalizedHeaders = mutableMapOf<String, String>()
        normalizedHeaders["Accept"] = "*/*"
        normalizedHeaders["Connection"] = "keep-alive"

        ch.headers?.forEach { (k, v) ->
            val key = when {
                k.equals("cookie", true) -> "Cookie"
                k.equals("user-agent", true) -> "User-Agent"
                else -> k
            }
            normalizedHeaders[key] = v
        }

        val channelUserAgent = ch.userAgent?.trim().orEmpty()
        val looksLikeRealUserAgent = channelUserAgent.contains("Mozilla", ignoreCase = true) ||
            channelUserAgent.contains("JioTV", ignoreCase = true) ||
            channelUserAgent.contains("AppleWebKit", ignoreCase = true) ||
            channelUserAgent.contains("Chrome", ignoreCase = true)
        if (looksLikeRealUserAgent && !normalizedHeaders.containsKey("User-Agent")) {
            normalizedHeaders["User-Agent"] = channelUserAgent
        }

        playerError = null
        retryCountRef.value = 0

        var resolvedLicenseUrl = ch.licenseUrl
        var playbackUrl = if (isFallbackAttempt) ch.m3u8Url ?: ch.mpdUrl ?: "" else ch.mpdUrl ?: ch.m3u8Url ?: ""

        val shouldUseWebView = playbackUrl.contains("tplay/play.php", true) || playbackUrl.contains("/tplay/play.php", true)
        if (shouldUseWebView) {
            try {
                val intent = android.content.Intent(context, com.skylake.skytv.jgorunner.activities.WebPlayerActivity::class.java).apply {
                    putExtra("startup_url", "https://allinonereborn.online/tplay/")
                    putExtra("target_channel_id", ch.id ?: "")
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                LogCollector.log("Failed to open WebPlayerActivity: ${e.message}")
            }
            showChannelOverlay = true
            overlayVisibilityTick = System.currentTimeMillis()
            return@LaunchedEffect
        }

        val alexJplusHost = resolvedLicenseUrl?.contains("alex4528.site", true) == true ||
            (ch.mpdUrl?.contains("alex4528.site", true) == true) ||
            (ch.m3u8Url?.contains("alex4528.site", true) == true)
        if (alexJplusHost) {
            if (!normalizedHeaders.containsKey("Origin")) {
                normalizedHeaders["Origin"] = "https://alex4528.site"
            }
            if (!normalizedHeaders.containsKey("Referer")) {
                normalizedHeaders["Referer"] = "https://alex4528.site/"
            }
        }

        // >>> JIO HEADERS FIX <<<
        if (playbackUrl.contains("jio.com", true) || playbackUrl.contains("jio.dev", true)) {
            val jioUA = "JioTV/7.0.8 (Linux; Android 13; Pixel 7 Pro Build/TQ1A.221205.011; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/110.0.5481.64 Mobile Safari/537.36"
            if (!normalizedHeaders.keys.any { it.equals("User-Agent", true) }) normalizedHeaders["User-Agent"] = jioUA
            if (!normalizedHeaders.keys.any { it.equals("Origin", true) }) normalizedHeaders["Origin"] = "https://www.jio.com"
            if (!normalizedHeaders.keys.any { it.equals("Referer", true) }) normalizedHeaders["Referer"] = "https://www.jio.com/"
            if (!normalizedHeaders.keys.any { it.equals("X-Requested-With", true) }) normalizedHeaders["X-Requested-With"] = "com.jio.jiotv"
            if (!normalizedHeaders.keys.any { it.equals("Accept", true) }) normalizedHeaders["Accept"] = "application/json, text/plain, */*"
            if (!normalizedHeaders.keys.any { it.equals("Accept-Language", true) }) normalizedHeaders["Accept-Language"] = "en-US,en;q=0.9"
        }

        // >>> EXTRACTOR FOR TATA BING <<<
        if (playbackUrl.contains("tplay/play.php", true)) {
            val playBody = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder()
                        .url(playbackUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "*/*")
                        .header("Referer", "https://allinonereborn.online/tplay/")
                        .build()
                    val response = okHttpClient.newCall(request).execute()
                    val body = response.body?.string()
                    response.close()
                    body
                } catch (e: Exception) {
                    LogCollector.log("Extraction Request Failed: ${e.message}")
                    null
                }
            }
            if (playBody != null) {
                // Highly robust regex: look for any .mpd or .m3u8 URL anywhere in the body
                val urlMatch = Regex("""https?://[^\s"'<>]+?\.(?:mpd|m3u8)(?:\?[^\s"'<>]+)?""").find(playBody)
                val drmMatch = Regex("""(?:drm|key|license|clearkey)\s*:\s*\{\s*["']?([^"'\s:]+)["']?\s*:\s*["']([^"']+)["']""").find(playBody)
                
                if (urlMatch != null) {
                    var parsedUrl = urlMatch.value
                    
                    // Handle tokens if present in the body but not in the URL
                    val tokenMatch = Regex("""token\s*:\s*["']([^"']+)["']""").find(playBody)
                    if (tokenMatch != null && !parsedUrl.contains("token=")) {
                        try {
                            val tokenValue = tokenMatch.groupValues[1]
                            val tokenPart = if (tokenValue.contains("?")) tokenValue.substringAfter("?") else tokenValue
                            if (tokenPart.isNotEmpty()) {
                                parsedUrl = if (parsedUrl.contains("?")) "$parsedUrl&$tokenPart" else "$parsedUrl?$tokenPart"
                            }
                        } catch(e:Exception){}
                    }
                    playbackUrl = parsedUrl
                    LogCollector.log("Extracted Tata URL: $playbackUrl")
                    
                    if (drmMatch != null) {
                        val kidHex = drmMatch.groupValues[1]
                        val kHex = drmMatch.groupValues[2]
                        try {
                            fun hexStringToByteArray(s: String): ByteArray {
                                val len = s.length
                                val data = ByteArray(len / 2)
                                var i = 0
                                while (i < len) {
                                    data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
                                    i += 2
                                }
                                return data
                            }
                            val kidBase64 = android.util.Base64.encodeToString(hexStringToByteArray(kidHex), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE).trim('=')
                            val kBase64 = android.util.Base64.encodeToString(hexStringToByteArray(kHex), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE).trim('=')
                            val clearKeyJson = """{"keys":[{"kty":"oct","k":"$kBase64","kid":"$kidBase64"}],"type":"temporary"}"""
                            resolvedLicenseUrl = "data:application/json;base64," + android.util.Base64.encodeToString(clearKeyJson.toByteArray(), android.util.Base64.NO_WRAP)
                        } catch(e: Exception) {}
                    }
                } else {
                    LogCollector.log("Extraction Failed: No media URL found in body. Body length: ${playBody.length}")
                    if (playBody.length < 1000) {
                        LogCollector.log("Body: $playBody")
                    } else {
                        LogCollector.log("Body preview: ${playBody.take(500)}")
                    }
                    playbackUrl = "" // Avoid ExoPlayer parsing HTML
                    playerError = "Stream provider changed link or requires redirect. Non-playable content received."
                }
            }
        }
        val isLocalPlayback = playbackUrl.contains("localhost", true) || playbackUrl.contains("127.0.0.1")
        val preferredPlaybackUrl =
            if (isLocalPlayback && !ch.m3u8Url.isNullOrBlank()) ch.m3u8Url ?: playbackUrl else playbackUrl

        if (playbackUrl.isNotBlank()) {
            val normalized = normalizePlaybackUrl(
                context,
                preferredPlaybackUrl,
                keepPlayEndpoint = !isLocalPlayback,
                applyQuality = false
            )
            LogCollector.log("Preparing Cloud Player: ${ch.name} -> $normalized")

            val builder = MediaItem.Builder()
                .setUri(normalized.toUri())
                .setMediaId(ch.id ?: "")

            // Resilient MimeType detection
            val isDash = !isFallbackAttempt &&
                !normalized.contains(".m3u8") &&
                !normalized.contains(".m3u") &&
                !normalized.contains("/live/") &&
                (
                    normalized.contains(".mpd") ||
                        normalized.contains("/play/") ||
                        normalized.contains("play.php") ||
                        normalized.contains("jio.com") ||
                        normalized.contains("jio.dev") ||
                        (ch.type == "dash" && !normalized.contains(".m3u8"))
                )
            lastAttemptWasDash = isDash

            if (isDash) {
                builder.setMimeType(MimeTypes.APPLICATION_MPD)
            } else if (normalized.contains(".m3u8") || normalized.contains(".m3u") || normalized.contains("/live/")) {
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }

            val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            val defaultRequestProperties = mutableMapOf<String, String>()
            normalizedHeaders.forEach { (k, v) -> defaultRequestProperties[k] = v }
            dataSourceFactory.setDefaultRequestProperties(defaultRequestProperties)

            val useDrm = !resolvedLicenseUrl.isNullOrBlank() && !isFallbackAttempt
            if (useDrm) {
                LogCollector.log("Configuring DRM: ${resolvedLicenseUrl}")

                val isClearKey = resolvedLicenseUrl!!.contains("plkey.php", true) ||
                                resolvedLicenseUrl!!.contains("key.php", true) ||
                                resolvedLicenseUrl!!.contains("clearkey", true) ||
                                resolvedLicenseUrl!!.contains("alex4528.site/jplus/license", true) ||
                                (resolvedLicenseUrl!!.contains("results.php", true) &&
                                    resolvedLicenseUrl!!.contains("keyid=", true) &&
                                    resolvedLicenseUrl!!.contains("key=", true)) ||
                                resolvedLicenseUrl!!.contains("data:application/json", true) ||
                                resolvedLicenseUrl!!.startsWith("data:", true) ||
                                ch.type?.contains("clearkey", true) == true

                val drmUuid = if (isClearKey) C.CLEARKEY_UUID else C.WIDEVINE_UUID

                builder.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(drmUuid)
                        .setLicenseUri(resolvedLicenseUrl)
                        .setLicenseRequestHeaders(normalizedHeaders)
                        .setMultiSession(true)
                        .build()
                )
            }

            val mediaItem = builder.build()
            val dashFactory = DashMediaSource.Factory(dataSourceFactory)
            val hlsFactory = HlsMediaSource.Factory(dataSourceFactory)

            if (useDrm) {
                val drmProvider = DefaultDrmSessionManagerProvider()
                drmProvider.setDrmHttpDataSourceFactory(dataSourceFactory)
                dashFactory.setDrmSessionManagerProvider(drmProvider)
                hlsFactory.setDrmSessionManagerProvider(drmProvider)
            }

            val mediaSource = if (isDash) {
                dashFactory.createMediaSource(mediaItem)
            } else {
                hlsFactory.createMediaSource(mediaItem)
            }

            exoPlayer.stop()
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            setupCustomPlaybackLogic(exoPlayer, normalized)
        }

        showChannelOverlay = true
        overlayVisibilityTick = System.currentTimeMillis()
    }

    LaunchedEffect(overlayVisibilityTick) {
        if (overlayVisibilityTick > 0L) {
            delay(5000)
            if (!showChannelPanel && !showSettingsPanel) {
                showChannelOverlay = false
            }
        }
    }

    LaunchedEffect(showChannelPanel) {
        if (showChannelPanel) {
            delay(100)
            sidePanelFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(showSettingsPanel) {
        if (showSettingsPanel) {
            delay(100)
            settingsPanelFocusRequester.requestFocus()
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            cleanupPlaybackLogic(exoPlayer)
            exoPlayer.release()
        }
    }

    BackHandler {
        if (showChannelPanel) {
            showChannelPanel = false
            rootFocusRequester.requestFocus()
        } else if (showSettingsPanel) {
            showSettingsPanel = false
            rootFocusRequester.requestFocus()
        } else {
            (context as? Activity)?.finish()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                if (event.key == Key.Back || event.key == Key.Escape) {
                    if (showSettingsPanel) {
                        showSettingsPanel = false
                        rootFocusRequester.requestFocus()
                        return@onPreviewKeyEvent true
                    }
                    if (showChannelPanel) {
                        showChannelPanel = false
                        rootFocusRequester.requestFocus()
                        return@onPreviewKeyEvent true
                    }
                }

                when (event.key) {
                    Key.DirectionLeft -> {
                        if (!showChannelPanel && !showSettingsPanel) {
                            panelSelectedIndex = currentIndex
                            showChannelPanel = true
                            return@onPreviewKeyEvent true
                        }
                    }
                    Key.DirectionRight -> {
                        if (!showChannelPanel && !showSettingsPanel) {
                            showSettingsPanel = true
                            return@onPreviewKeyEvent true
                        }
                    }
                    Key.DirectionUp -> {
                        if (!showChannelPanel && !showSettingsPanel) {
                            currentIndex = (currentIndex - 1 + activeList.size) % activeList.size
                            return@onPreviewKeyEvent true
                        }
                    }
                    Key.DirectionDown -> {
                        if (!showChannelPanel && !showSettingsPanel) {
                            currentIndex = (currentIndex + 1) % activeList.size
                            return@onPreviewKeyEvent true
                        }
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (!showChannelPanel && !showSettingsPanel) {
                            showChannelOverlay = true
                            overlayVisibilityTick = System.currentTimeMillis()
                            overlayFocusRequester.requestFocus()
                            return@onPreviewKeyEvent true
                        }
                    }
                }

                val digit = when (event.key) {
                    Key.Zero -> 0; Key.One -> 1; Key.Two -> 2; Key.Three -> 3; Key.Four -> 4
                    Key.Five -> 5; Key.Six -> 6; Key.Seven -> 7; Key.Eight -> 8; Key.Nine -> 9
                    else -> null
                }
                if (digit != null) {
                    numericBuffer += digit.toString()
                    showNumericOverlay = true
                    numericJob?.cancel()
                    numericJob = scope.launch {
                        delay(1500)
                        val num = numericBuffer.toIntOrNull()
                        if (num != null && num in 1..activeList.size) {
                            currentIndex = num - 1
                        }
                        numericBuffer = ""
                        showNumericOverlay = false
                    }
                    return@onPreviewKeyEvent true
                }

                false
            }
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    setKeepContentOnPlayerReset(true)
                    player = exoPlayer
                    exoPlayerView = this
                    this.resizeMode = currentResizeMode
                }
            },
            update = { view ->
                view.player = exoPlayer
                if (view.resizeMode != currentResizeMode) {
                    view.resizeMode = currentResizeMode
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {
                    showChannelOverlay = true
                    overlayVisibilityTick = System.currentTimeMillis()
                }
        )

        // Subtle indicator for fallback or initial loading - text removed as requested
        if (isSilentTransition) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(32.dp))
            }
        }

        // Overlay UI
        if (showChannelOverlay && !showChannelPanel && !showSettingsPanel) {
            CloudPlayerOverlay(
                channel = activeCloudChannel,
                currentIndex = currentIndex,
                focusRequester = overlayFocusRequester,
                onMenuClick = { showSettingsPanel = true },
                onChannelsClick = {
                    panelSelectedIndex = currentIndex
                    showChannelPanel = true
                },
                onRefreshClick = {
                    val current = currentIndex
                    currentIndex = -1
                    scope.launch { delay(100); currentIndex = current }
                }
            )
        }

        // Side Panels
        AnimatedVisibility(
            visible = showChannelPanel,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it }
        ) {
            CloudSidePanel(
                channels = activeList,
                selectedIndex = panelSelectedIndex,
                focusRequester = sidePanelFocusRequester,
                onChannelSelected = {
                    currentIndex = it
                    showChannelPanel = false
                    rootFocusRequester.requestFocus()
                },
                onClose = {
                    showChannelPanel = false
                    rootFocusRequester.requestFocus()
                }
            )
        }

        AnimatedVisibility(
            visible = showSettingsPanel,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            CloudSettingsPanel(
                preferenceManager = preferenceManager,
                focusRequester = settingsPanelFocusRequester,
                currentResizeMode = currentResizeMode,
                onResizeModeChange = { currentResizeMode = it },
                onClose = {
                    showSettingsPanel = false
                    rootFocusRequester.requestFocus()
                }
            )
        }

        if (showNumericOverlay) {
            Text(
                text = numericBuffer,
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 50.dp)
            )
        }

        if (playerError != null && !isSilentTransition) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Playback Error", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(playerError!!, color = Color.Gray, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = {
                        playerError = null
                        val c = currentIndex
                        currentIndex = -1
                        scope.launch { delay(100); currentIndex = c }
                    }) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
fun CloudPlayerOverlay(
    channel: CloudChannel?,
    currentIndex: Int,
    focusRequester: FocusRequester,
    onMenuClick: () -> Unit,
    onChannelsClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column(modifier = Modifier.align(Alignment.BottomStart)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = channel?.logo,
                    contentDescription = null,
                    modifier = Modifier.size(66.dp).clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(text = "${currentIndex + 1}. ${channel?.name ?: "Unknown"}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    Text(text = channel?.group ?: "", color = Color.Cyan.copy(alpha = 0.7f), fontSize = 16.sp)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row {
                OverlayButton(onClick = onChannelsClick, icon = Icons.AutoMirrored.Filled.List, modifier = Modifier.focusRequester(focusRequester))
                Spacer(modifier = Modifier.width(12.dp))
                OverlayButton(onClick = onMenuClick, icon = Icons.Default.Settings)
                Spacer(modifier = Modifier.width(12.dp))
                OverlayButton(onClick = onRefreshClick, icon = Icons.Default.Refresh)
            }
        }

        Card(
            modifier = Modifier.align(Alignment.TopEnd),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(8.dp)
        ) {
            var time by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                while(true) {
                    val cal = Calendar.getInstance()
                    time = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
                    delay(30000)
                }
            }
            Text(text = time, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
fun OverlayButton(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }
    IconButton(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .background(
                if (isFocused) Color.Cyan.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.5f),
                RoundedCornerShape(50)
            )
            .border(if (isFocused) 2.dp else 0.dp, Color.Cyan, RoundedCornerShape(50))
    ) {
        Icon(icon, null, tint = if (isFocused) Color.Cyan else Color.White)
    }
}

@Composable
fun CloudSidePanel(
    channels: List<CloudChannel>,
    selectedIndex: Int,
    focusRequester: FocusRequester,
    onChannelSelected: (Int) -> Unit,
    onClose: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) listState.scrollToItem(selectedIndex)
    }

    Box(modifier = Modifier.fillMaxHeight().width(320.dp).background(Color.Black.copy(alpha = 0.85f)).padding(16.dp)) {
        Column {
            Text("Channels", color = Color.Cyan, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(bottom = 16.dp))
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexed(channels) { index, channel ->
                    val isSelected = index == selectedIndex
                    var isFocused by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == selectedIndex) Modifier.focusRequester(focusRequester) else Modifier)
                            .clip(RoundedCornerShape(8.dp))
                            .onFocusChanged { isFocused = it.isFocused }
                            .background(
                                if (isFocused) Color.Cyan.copy(alpha = 0.3f)
                                else if (isSelected) Color.Cyan.copy(alpha = 0.1f)
                                else Color.Transparent
                            )
                            .border(if (isFocused) 2.dp else 0.dp, Color.Cyan, RoundedCornerShape(8.dp))
                            .focusable()
                            .clickable { onChannelSelected(index) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = channel.name,
                            color = if (isFocused || isSelected) Color.Cyan else Color.White,
                            maxLines = 1,
                            fontSize = 13.sp,
                            fontWeight = if (isFocused || isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun CloudSettingsPanel(
    preferenceManager: SkySharedPref,
    focusRequester: FocusRequester,
    currentResizeMode: Int,
    onResizeModeChange: (Int) -> Unit,
    onClose: () -> Unit
) {
    val modes = listOf(
        "Fit" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
        "Fill" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
        "Zoom" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        "Fixed Width" to AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
        "Fixed Height" to AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
    )

    val qualityOptions = listOf(
        "Auto" to 0,
        "144p" to 144,
        "240p" to 240,
        "360p" to 360,
        "480p" to 480,
        "720p" to 720,
        "1080p" to 1080,
        "1440p" to 1440,
        "2160p (4K)" to 2160
    )
    val qualityLabels = qualityOptions.map { it.first }
    val currentMaxHeight = preferenceManager.myPrefs.cloudQualityMaxHeight
    val initialQualityLabel =
        qualityOptions.firstOrNull { it.second == currentMaxHeight }?.first ?: "Auto"

    var showQualityDialog by remember { mutableStateOf(false) }
    var currentQ by remember { mutableStateOf(initialQualityLabel) }

    Box(modifier = Modifier.fillMaxHeight().width(280.dp).background(Color.Black.copy(alpha = 0.85f)).padding(16.dp)) {
        Column {
            Text("Player Settings", color = Color.Cyan, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(bottom = 16.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    var checked by remember { mutableStateOf(preferenceManager.myPrefs.cloudAutoplayLastChannel) }
                    SettingsToggleRefreshed("Autoplay Last played channel", checked) {
                        checked = it
                        preferenceManager.myPrefs.cloudAutoplayLastChannel = it
                        preferenceManager.savePreferences()
                    }
                }
                item {
                    val currentLabel = modes.find { it.second == currentResizeMode }?.first ?: "Fit"
                    SettingsActionItemCompact("Aspect Ratio: $currentLabel", Icons.Default.AspectRatio, modifier = Modifier.focusRequester(focusRequester)) {
                        val currentIndex = modes.indexOfFirst { it.second == currentResizeMode }
                        val nextIndex = (currentIndex + 1) % modes.size
                        onResizeModeChange(modes[nextIndex].second)
                    }
                }
                item {
                    SettingsActionItemCompact("Quality: $currentQ", Icons.Default.HighQuality) {
                        showQualityDialog = true
                    }
                }
                item {
                    SettingsActionItemCompact("Close Menu", Icons.Default.Close) { onClose() }
                }
            }
        }
    }

    if (showQualityDialog) {
        val selected = setOf(currentQ)
        MultiSelectFilterDialog(
            title = "Quality",
            options = qualityLabels,
            selectedOptions = selected,
            singleSelect = true,
            onDismiss = { showQualityDialog = false },
            onConfirm = { selectedLabels ->
                val label = selectedLabels.firstOrNull() ?: "Auto"
                val index = qualityLabels.indexOf(label).let { if (it < 0) 0 else it }
                currentQ = label
                preferenceManager.myPrefs.cloudQualityMaxHeight = qualityOptions[index].second
                preferenceManager.savePreferences()
                showQualityDialog = false
                onClose()
            }
        )
    }
}

@Composable
fun SettingsActionItemCompact(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(6.dp))
            .background(if (isFocused) Color.Cyan.copy(alpha = 0.15f) else Color.Transparent)
            .clickable { onClick() }
            .focusable()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (isFocused) Color.Cyan else Color.Gray, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, color = if (isFocused) Color.White else Color.Gray, fontSize = 11.sp)
    }
}
