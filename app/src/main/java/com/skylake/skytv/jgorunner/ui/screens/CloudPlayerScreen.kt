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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.skylake.skytv.jgorunner.activities.MainActivity
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.ui.tvhome.CloudChannel
import com.skylake.skytv.jgorunner.utils.LogCollector
import com.skylake.skytv.jgorunner.utils.normalizePlaybackUrl
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

    var currentIndex by remember { mutableIntStateOf(initialIndex) }
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

    val headerState = remember { mutableStateOf<Map<String, String>?>(null) }
    val userAgentState = remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val dataSourceFactory = object : androidx.media3.datasource.DataSource.Factory {
            override fun createDataSource(): androidx.media3.datasource.DataSource {
                val factory = OkHttpDataSource.Factory(okHttpClient)
                    .setUserAgent(userAgentState.value ?: "@cloudplay")
                headerState.value?.let { factory.setDefaultRequestProperties(it) }
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
            val builder = MediaItem.Builder().setUri(normalized.toUri())

            if (normalized.contains(".mpd") || ch?.type == "dash") {
                builder.setMimeType(MimeTypes.APPLICATION_MPD)
            } else if (normalized.contains(".m3u8")) {
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }

            ch?.licenseUrl?.let { lic ->
                LogCollector.log("Setting Cloud DRM: $lic")
                builder.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(androidx.media3.common.C.WIDEVINE_UUID)
                        .setLicenseUri(lic)
                        .setLicenseRequestHeaders(ch.headers ?: emptyMap())
                        .setMultiSession(true)
                        .setForceDefaultLicenseUri(true)
                        .build()
                )
            }

            exoPlayer.setMediaItem(builder.build())
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }

        showChannelOverlay = true
        overlayVisibilityTick = System.currentTimeMillis()
    }

    LaunchedEffect(overlayVisibilityTick) {
        if (overlayVisibilityTick > 0L) {
            kotlinx.coroutines.delay(5000)
            if (!showChannelPanel) {
                showChannelOverlay = false
            }
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
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
                        } else {
                            currentIndex = (currentIndex - 1 + cloudChannelList.size) % cloudChannelList.size
                        }
                        return@onPreviewKeyEvent true
                    }
                    Key.DirectionDown -> {
                        if (showChannelPanel) {
                            panelSelectedIndex = (panelSelectedIndex + 1) % cloudChannelList.size
                        } else {
                            currentIndex = (currentIndex + 1) % cloudChannelList.size
                        }
                        return@onPreviewKeyEvent true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (showChannelPanel) {
                            currentIndex = panelSelectedIndex
                            showChannelPanel = false
                        } else {
                            showChannelOverlay = true
                            overlayVisibilityTick = System.currentTimeMillis()
                        }
                        return@onPreviewKeyEvent true
                    }
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
                    val c = currentIndex
                    currentIndex = -1
                    Handler(Looper.getMainLooper()).postDelayed({ currentIndex = c }, 100)
                }
            )
        }

        if (showChannelPanel) {
            Box(modifier = Modifier.fillMaxHeight().width(300.dp).background(Color.Black.copy(alpha = 0.8f))) {
                val listState = rememberLazyListState()
                LaunchedEffect(Unit) { listState.scrollToItem(panelSelectedIndex) }
                LazyColumn(state = listState) {
                    itemsIndexed(cloudChannelList) { index, channel ->
                        val isSelected = index == panelSelectedIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) Color.Cyan.copy(alpha = 0.3f) else Color.Transparent)
                                .clickable { currentIndex = index; showChannelPanel = false }
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

        if (playerError != null) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Playback Error", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(playerError!!, color = Color.Red)
                    Button(onClick = { playerError = null; val c = currentIndex; currentIndex = -1; Handler(Looper.getMainLooper()).postDelayed({ currentIndex = c }, 100) }) {
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
                AsyncImage(model = channel?.logo, contentDescription = null, modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)))
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

        Card(
            modifier = Modifier.align(Alignment.TopEnd),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f))
        ) {
            var time by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                while(true) {
                    val cal = Calendar.getInstance()
                    time = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
                    kotlinx.coroutines.delay(30000)
                }
            }
            Text(text = time, color = Color.White, modifier = Modifier.padding(8.dp), fontWeight = FontWeight.Bold)
        }
    }
}
