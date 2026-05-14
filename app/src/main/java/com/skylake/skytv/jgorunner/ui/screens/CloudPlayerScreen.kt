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
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.skylake.skytv.jgorunner.activities.MainActivity
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.ui.tvhome.CloudChannel
import com.skylake.skytv.jgorunner.utils.LogCollector
import com.skylake.skytv.jgorunner.utils.normalizePlaybackUrl
import com.skylake.skytv.jgorunner.utils.setupCustomPlaybackLogic
import com.skylake.skytv.jgorunner.utils.cleanupPlaybackLogic
import com.skylake.skytv.jgorunner.utils.CloudMediaDrmCallback
import com.skylake.skytv.jgorunner.data.CloudDataManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Calendar
import java.util.UUID
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

    var isFallbackAttempt by remember(currentIndex) { mutableStateOf(false) }
    var isSilentTransition by remember(currentIndex) { mutableStateOf(false) }

    var currentResizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    val okHttpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(35, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        LogCollector.logError("CloudPlayer Error: ${error.errorCodeName} - ${error.message}", error)

                        if (!isFallbackAttempt && activeCloudChannel?.m3u8Url != null &&
                            (error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                             error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                             error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)) {

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

    LaunchedEffect(currentIndex, isFallbackAttempt) {
        val ch = activeList.getOrNull(currentIndex)
        if (ch == null) return@LaunchedEffect

        activeCloudChannel = ch

        val isJio = ch.mpdUrl?.contains("jio.com", true) == true ||
                   ch.m3u8Url?.contains("jio.com", true) == true ||
                   ch.licenseUrl?.contains("webplay.fun", true) == true ||
                   ch.licenseUrl?.contains("jio", true) == true

        val isAlex = ch.mpdUrl?.contains("alex4528.site", true) == true ||
                    ch.licenseUrl?.contains("alex4528.site", true) == true
        val isWebPlay = ch.mpdUrl?.contains("webplay.fun", true) == true ||
                       ch.licenseUrl?.contains("webplay.fun", true) == true

        val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "0123456789abcdef"

        val finalUA = when {
            ch.userAgent != null && ch.userAgent != "@cloudplay" && ch.userAgent.isNotBlank() -> ch.userAgent
            isJio -> "plaYtv/7.1.3 (Linux;Android 14)"
            else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        }

        val normalizedHeaders = mutableMapOf<String, String>()
        normalizedHeaders["User-Agent"] = finalUA
        normalizedHeaders["Accept"] = "*/*"
        normalizedHeaders["Connection"] = "keep-alive"

        if (isJio) {
            normalizedHeaders["os"] = "android"
            normalizedHeaders["devicetype"] = "phone"
            normalizedHeaders["uniqueId"] = androidId
            normalizedHeaders["deviceId"] = androidId
            normalizedHeaders["appname"] = "com.jio.jiotv"
            normalizedHeaders["versionCode"] = "323"
            normalizedHeaders["X-Jio-Network-Type"] = "WIFI"
            normalizedHeaders["X-Requested-With"] = "com.jio.jiotv"
            normalizedHeaders["Origin"] = "https://www.jio.com"
        }

        if (isAlex || isWebPlay) {
            normalizedHeaders["Origin"] = if (isAlex) "https://alex4528.site" else "https://temp.webplay.fun"
            normalizedHeaders["Referer"] = if (isAlex) "https://alex4528.site/" else "https://temp.webplay.fun/"
            normalizedHeaders["Sec-Fetch-Mode"] = "cors"
            normalizedHeaders["Sec-Fetch-Site"] = if (isAlex) "same-origin" else "cross-site"
            normalizedHeaders["Sec-Fetch-Dest"] = "empty"
        }

        ch.headers?.forEach { (k, v) ->
            val key = when {
                k.equals("cookie", true) -> "Cookie"
                k.equals("user-agent", true) -> "User-Agent"
                else -> k
            }
            normalizedHeaders[key] = v
        }

        playerError = null
        retryCountRef.value = 0

        val playbackUrl = if (isFallbackAttempt) ch.m3u8Url ?: ch.mpdUrl ?: "" else ch.mpdUrl ?: ch.m3u8Url ?: ""

        if (playbackUrl.isNotBlank()) {
            val normalized = normalizePlaybackUrl(context, playbackUrl)
            LogCollector.log("Preparing Cloud Player: ${ch.name} -> $normalized")

            val builder = MediaItem.Builder()
                .setUri(normalized.toUri())
                .setMediaId(ch.id ?: "")

            // Resilient MimeType detection
            val isDash = !isFallbackAttempt && (
                normalized.contains(".mpd") ||
                normalized.contains("/play/") ||
                (ch.type == "dash" && !normalized.contains(".m3u8"))
            )

            if (isDash) {
                builder.setMimeType(MimeTypes.APPLICATION_MPD)
            } else if (normalized.contains(".m3u8") || normalized.contains(".m3u") || normalized.contains("/live/")) {
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }

            val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            val defaultRequestProperties = mutableMapOf<String, String>()
            normalizedHeaders.forEach { (k, v) -> defaultRequestProperties[k] = v }
            dataSourceFactory.setDefaultRequestProperties(defaultRequestProperties)

            if (!ch.licenseUrl.isNullOrBlank() && !isFallbackAttempt) {
                LogCollector.log("Configuring DRM: ${ch.licenseUrl}")

                val isClearKey = ch.licenseUrl.contains("plkey.php", true) ||
                                ch.licenseUrl.contains("key.php", true) ||
                                ch.licenseUrl.contains("clearkey", true) ||
                                ch.type?.contains("clearkey", true) == true

                val drmUuid = if (isClearKey) C.CLEARKEY_UUID else C.WIDEVINE_UUID

                builder.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(drmUuid)
                        .setLicenseUri(ch.licenseUrl)
                        .setMultiSession(true)
                        .build()
                )

                val drmCallback = CloudMediaDrmCallback(ch.licenseUrl!!, normalizedHeaders, okHttpClient)
                val drmSessionManager = DefaultDrmSessionManager.Builder()
                    .setMultiSession(true)
                    .setUuidAndExoMediaDrmProvider(drmUuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .build(drmCallback)

                val mediaItem = builder.build()
                val mediaSource = if (isDash) {
                    DashMediaSource.Factory(dataSourceFactory)
                        .setDrmSessionManagerProvider { drmSessionManager }
                        .createMediaSource(mediaItem)
                } else {
                    HlsMediaSource.Factory(dataSourceFactory)
                        .setDrmSessionManagerProvider { drmSessionManager }
                        .createMediaSource(mediaItem)
                }
                exoPlayer.setMediaSource(mediaSource)
            } else {
                val mediaItem = builder.build()
                val mediaSource = if (isDash) {
                    DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                } else {
                    HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                }
                exoPlayer.setMediaSource(mediaSource)
            }

            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.setMediaItem(builder.build()) // will be overwritten if DRM used below, but good for base
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
                },
                onLogClick = { showSettingsPanel = false }
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

        var showPlayerLogDialog by remember { mutableStateOf(false) }

        if (playerError != null && !isSilentTransition) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Playback Error", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(playerError!!, color = Color.Gray, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(modifier = Modifier.height(24.dp))
                    Row {
                        Button(onClick = {
                            playerError = null
                            val c = currentIndex
                            currentIndex = -1
                            scope.launch { delay(100); currentIndex = c }
                        }) {
                            Text("Retry")
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Button(
                            onClick = { showPlayerLogDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                        ) {
                            Text("Show Logs")
                        }
                    }
                }
            }
        }

        if (showPlayerLogDialog) {
            LogViewerDialog(
                onDismiss = { showPlayerLogDialog = false },
                onCopy = { LogCollector.copyToClipboard(context) }
            )
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
                    modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp))
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
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = channel.name,
                            color = if (isFocused || isSelected) Color.Cyan else Color.White,
                            maxLines = 1,
                            fontSize = 14.sp,
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
    onClose: () -> Unit,
    onLogClick: () -> Unit
) {
    val modes = listOf(
        "Fit" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
        "Fill" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
        "Zoom" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        "Fixed Width" to AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
        "Fixed Height" to AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
    )

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
                    var checked by remember { mutableStateOf(preferenceManager.myPrefs.cloudFocusAnimationEnabled) }
                    SettingsToggleRefreshed("Focus Glow", checked) {
                        checked = it
                        preferenceManager.myPrefs.cloudFocusAnimationEnabled = it
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
                    SettingsActionItemCompact("Playback Stats", Icons.Default.BarChart) { /* Logic */ }
                }
                item {
                    SettingsActionItemCompact("Playback Logs", Icons.Default.BugReport) { onLogClick() }
                }
                item {
                    SettingsActionItemCompact("Close Menu", Icons.Default.Close) { onClose() }
                }
            }
        }
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
