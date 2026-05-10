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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.skylake.skytv.jgorunner.activities.MainActivity
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.ui.tvhome.CloudChannel
import com.skylake.skytv.jgorunner.utils.LogCollector
import com.skylake.skytv.jgorunner.utils.normalizePlaybackUrl
import com.skylake.skytv.jgorunner.utils.setupCustomPlaybackLogic
import com.skylake.skytv.jgorunner.utils.cleanupPlaybackLogic
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
    initialIndex: Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentIndex by remember(initialIndex) { mutableIntStateOf(initialIndex) }
    var activeCloudChannel by remember(currentIndex) {
        mutableStateOf(cloudChannelList.getOrNull(currentIndex))
    }

    val focusRequester = remember { FocusRequester() }
    var showChannelPanel by remember { mutableStateOf(false) }
    var panelSelectedIndex by remember { mutableIntStateOf(currentIndex) }
    var showChannelOverlay by remember { mutableStateOf(false) }
    var overlayVisibilityTick by remember { mutableLongStateOf(0L) }
    var playerError by remember { mutableStateOf<String?>(null) }
    val retryCountRef = remember { mutableIntStateOf(0) }
    var exoPlayerView: PlayerView? by remember { mutableStateOf(null) }
    var numericBuffer by remember { mutableStateOf("") }
    var showNumericOverlay by remember { mutableStateOf(false) }
    var numericJob by remember { mutableStateOf<Job?>(null) }

    val headerState = remember { mutableStateOf<Map<String, String>?>(null) }
    val userAgentState = remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember {
        val dataSourceFactory = object : androidx.media3.datasource.DataSource.Factory {
            override fun createDataSource(): androidx.media3.datasource.DataSource {
                val factory = DefaultHttpDataSource.Factory()
                    .setUserAgent(userAgentState.value ?: "@cloudplay")
                    .setAllowCrossProtocolRedirects(true)

                // Propagate headers with normalized keys
                headerState.value?.let { h ->
                    val normalized = h.mapKeys { (k, _) ->
                        when {
                            k.equals("cookie", true) -> "Cookie"
                            k.equals("user-agent", true) -> "User-Agent"
                            else -> k
                        }
                    }
                    factory.setDefaultRequestProperties(normalized)
                }
                return factory.createDataSource()
            }
        }

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        LogCollector.log("CloudPlayer Error: ${error.errorCodeName} - ${error.message}")
                        playerError = error.errorCodeName

                        // Auto-retry logic
                        if (retryCountRef.value < 5) {
                            retryCountRef.value++
                            Handler(Looper.getMainLooper()).postDelayed({
                                prepare()
                                play()
                            }, 2000)
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            retryCountRef.value = 0
                            playerError = null
                        }
                    }
                })
            }
    }

    LaunchedEffect(currentIndex) {
        val ch = cloudChannelList.getOrNull(currentIndex)
        activeCloudChannel = ch
        headerState.value = ch?.headers
        userAgentState.value = ch?.userAgent

        playerError = null
        retryCountRef.value = 0

        val playbackUrl = ch?.mpdUrl ?: ch?.m3u8Url ?: ""
        if (playbackUrl.isNotBlank()) {
            val normalized = normalizePlaybackUrl(context, playbackUrl)
            LogCollector.log("Playing Cloud URL: $normalized")

            val builder = MediaItem.Builder().setUri(normalized.toUri())

            if (normalized.contains(".mpd") || ch?.type == "dash") {
                builder.setMimeType(MimeTypes.APPLICATION_MPD)
            } else if (normalized.contains(".m3u8")) {
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }

            ch?.licenseUrl?.let { lic ->
                LogCollector.log("Setting Cloud DRM: $lic")

                val drmHeaders = mutableMapOf<String, String>()
                ch.userAgent?.let { drmHeaders["User-Agent"] = it }
                ch.headers?.forEach { (k, v) ->
                    when {
                        k.equals("cookie", true) -> drmHeaders["Cookie"] = v
                        k.equals("user-agent", true) -> drmHeaders["User-Agent"] = v
                        else -> drmHeaders[k] = v
                    }
                }

                builder.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                        .setLicenseUri(lic)
                        .setLicenseRequestHeaders(drmHeaders)
                        .setMultiSession(true)
                        .setForceDefaultLicenseUri(true)
                        .build()
                )
            }

            exoPlayer.setMediaItem(builder.build())
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
            if (!showChannelPanel) {
                showChannelOverlay = false
            }
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
        } else {
            (context as? Activity)?.finish()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) {
                showChannelOverlay = true
                overlayVisibilityTick = System.currentTimeMillis()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                when (event.key) {
                    Key.DirectionLeft -> {
                        if (!showChannelPanel) {
                            panelSelectedIndex = currentIndex
                            showChannelPanel = true
                            return@onPreviewKeyEvent true
                        }
                    }
                    Key.DirectionUp -> {
                        if (showChannelPanel) {
                            panelSelectedIndex = (panelSelectedIndex - 1 + cloudChannelList.size) % cloudChannelList.size
                            return@onPreviewKeyEvent true
                        } else {
                            currentIndex = (currentIndex - 1 + cloudChannelList.size) % cloudChannelList.size
                            return@onPreviewKeyEvent true
                        }
                    }
                    Key.DirectionDown -> {
                        if (showChannelPanel) {
                            panelSelectedIndex = (panelSelectedIndex + 1) % cloudChannelList.size
                            return@onPreviewKeyEvent true
                        } else {
                            currentIndex = (currentIndex + 1) % cloudChannelList.size
                            return@onPreviewKeyEvent true
                        }
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (showChannelPanel) {
                            currentIndex = panelSelectedIndex
                            showChannelPanel = false
                            return@onPreviewKeyEvent true
                        } else {
                            showChannelOverlay = true
                            overlayVisibilityTick = System.currentTimeMillis()
                        }
                    }
                    Key.Back -> {
                        if (showChannelPanel) {
                            showChannelPanel = false
                            return@onPreviewKeyEvent true
                        }
                    }
                }

                // Numeric entry
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
                        if (num != null && num in 1..cloudChannelList.size) {
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
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )

        // Overlays
        if (showChannelOverlay) {
            CloudPlayerOverlay(
                channel = activeCloudChannel,
                currentIndex = currentIndex,
                onMenuClick = { showChannelPanel = true },
                onHomeClick = {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        putExtra("target_screen", "CloudHome")
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    context.startActivity(intent)
                    (context as? Activity)?.finish()
                },
                onDashboardClick = {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        putExtra("target_screen", "CloudMain")
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    context.startActivity(intent)
                    (context as? Activity)?.finish()
                },
                onRefreshClick = {
                    val current = currentIndex
                    currentIndex = -1
                    scope.launch { delay(100); currentIndex = current }
                }
            )
        }

        if (showChannelPanel) {
            CloudSidePanel(
                channels = cloudChannelList,
                selectedIndex = panelSelectedIndex,
                onChannelSelected = {
                    currentIndex = it
                    showChannelPanel = false
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

        if (playerError != null) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Playback Error", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(playerError!!, color = Color.Red, fontSize = 12.sp)
                    Button(onClick = { playerError = null; val c = currentIndex; currentIndex = -1; scope.launch { delay(100); currentIndex = c } }) {
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
    onMenuClick: () -> Unit,
    onHomeClick: () -> Unit,
    onDashboardClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(
            modifier = Modifier.align(Alignment.TopStart),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = channel?.logo,
                    contentDescription = null,
                    modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = "${currentIndex + 1}. ${channel?.name ?: "Unknown"}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(text = channel?.group ?: "", color = Color.Gray, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.width(24.dp))
                IconButton(onClick = onMenuClick) { Icon(Icons.Default.Menu, null, tint = Color.White) }
                IconButton(onClick = onHomeClick) { Icon(Icons.Default.Home, null, tint = Color.White) }
                IconButton(onClick = onDashboardClick) { Icon(Icons.Default.Dashboard, null, tint = Color.White) }
                IconButton(onClick = onRefreshClick) { Icon(Icons.Default.Refresh, null, tint = Color.White) }
            }
        }

        // Clock
        Card(
            modifier = Modifier.align(Alignment.TopEnd),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f))
        ) {
            var time by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                while(true) {
                    val cal = Calendar.getInstance()
                    time = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
                    delay(30000)
                }
            }
            Text(text = time, color = Color.White, modifier = Modifier.padding(8.dp), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun CloudSidePanel(
    channels: List<CloudChannel>,
    selectedIndex: Int,
    onChannelSelected: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) { listState.animateScrollToItem(selectedIndex) }

    Box(modifier = Modifier.fillMaxHeight().width(300.dp).background(Color.Black.copy(alpha = 0.8f))) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(channels) { index, channel ->
                val isSelected = index == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) Color.Cyan.copy(alpha = 0.3f) else Color.Transparent)
                        .clickable { onChannelSelected(index) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = channel.name, color = Color.White, maxLines = 1)
                }
            }
        }
    }
}
