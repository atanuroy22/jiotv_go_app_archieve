package com.skylake.skytv.jgorunner.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesomeMotion
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
import androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.skylake.skytv.jgorunner.R
import com.skylake.skytv.jgorunner.activities.ChannelInfo
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.receivers.PipActionReceiver
import com.skylake.skytv.jgorunner.services.player.PlayerCommandBus
import com.skylake.skytv.jgorunner.ui.tvhome.ChannelUtils
import com.skylake.skytv.jgorunner.ui.tvhome.extractChannelIdFromPlayUrl
import com.skylake.skytv.jgorunner.utils.DeviceUtils
import com.skylake.skytv.jgorunner.utils.cleanupPlaybackLogic
import com.skylake.skytv.jgorunner.utils.containsAnyId
import com.skylake.skytv.jgorunner.utils.normalizePlaybackUrl
import com.skylake.skytv.jgorunner.utils.setupCustomPlaybackLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private class ZoneShakaBridge(
    private val onVisibilityChanged: (Boolean) -> Unit
) {
    @JavascriptInterface
    fun onControllerVisibilityChanged(visible: Boolean) {
        onVisibilityChanged(visible)
    }
}

const val TAG = "ExoJetScreen"

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3ExpressiveApi::class)
@SuppressLint("AutoboxingStateCreation", "DefaultLocale")
@Composable
fun ExoPlayJetScreen(
    preferenceManager: SkySharedPref,
    videoUrl: String,
    channelList: ArrayList<ChannelInfo>?,
    currentChannelIndex: Int
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val localPORT by remember { mutableIntStateOf(preferenceManager.myPrefs.jtvGoServerPort) }
    val basefinURL = "http://localhost:$localPORT"
    val tvNAV = preferenceManager.myPrefs.selectedRemoteNavTV ?: "0"
    val pipEnabled = preferenceManager.myPrefs.enablePip
    val isTv = remember {
        try {
            val pm = context.packageManager
            pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        } catch (_: Exception) {
            false
        }
    }
    val overlayDisplayTimeMs = if (isTv) 1200 else 3000
    val focusRequester = remember { FocusRequester() }
    var currentIndex by remember { mutableStateOf(currentChannelIndex) }
    var overrideVideoUrl by remember { mutableStateOf<String?>(null) }
    var showChannelPanel by remember { mutableStateOf(false) }
    var panelSelectedIndex by remember { mutableStateOf(currentChannelIndex.coerceAtLeast(0)) }
    var currentProgramName by remember { mutableStateOf<String?>(null) }
    var showChannelOverlay by remember { mutableStateOf(false) }
    val retryCountRef = remember { mutableStateOf(0) }
    var exoPlayerView: PlayerView? by remember { mutableStateOf(null) }
    var numericBuffer by remember { mutableStateOf("") }
    var showNumericOverlay by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var numericJob: Job? by remember { mutableStateOf(null) }
    var isControllerVisible by remember { mutableStateOf(false) }
    var controllerAutoHideJob: Job? by remember { mutableStateOf(null) }
    var zoneWebView: WebView? by remember { mutableStateOf(null) }
    var isWebLoading by remember { mutableStateOf(false) }
    var lastLoadedZoneDrmUrl by remember { mutableStateOf<String?>(null) }
    var zoneWebRetryCount by remember { mutableIntStateOf(0) }
    var zoneWebRetryJob: Job? by remember { mutableStateOf(null) }
    var isZoneShakaControllerVisible by remember { mutableStateOf(false) }
    var suppressNextZoneCenterKeyUp by remember { mutableStateOf(false) }

    val activeUrlRaw = overrideVideoUrl ?: channelList?.getOrNull(currentIndex)?.videoUrl ?: videoUrl
    val normalizedActiveUrl = remember(activeUrlRaw, currentIndex, channelList, videoUrl) {
        normalizePlaybackUrl(context, activeUrlRaw)
    }
    val useZoneDrmWebPlayer = remember(activeUrlRaw, normalizedActiveUrl) {
        // Detect DRM from raw URL first; normalization can transform /play/ into /live/*.m3u8.
        isLikelyDrmRoute(activeUrlRaw) || isLikelyDrmRoute(normalizedActiveUrl)
    }
    val zoneDrmStartupUrl = remember(activeUrlRaw, localPORT, preferenceManager.myPrefs.filterQX) {
        toZoneDrmUrl(
            inputUrl = activeUrlRaw,
            localPort = localPORT,
            quality = preferenceManager.myPrefs.filterQX
        )
    }

        LaunchedEffect(configuration.orientation, useZoneDrmWebPlayer) {
                if (!useZoneDrmWebPlayer) return@LaunchedEffect
                delay(140)
                zoneWebView?.evaluateJavascript(
                        """
                        (function() {
                            try {
                                if (window.__zoneApplyViewportFix) {
                                    window.__zoneApplyViewportFix();
                                }
                                window.dispatchEvent(new Event('resize'));
                                setTimeout(function() { window.dispatchEvent(new Event('resize')); }, 140);
                            } catch (e) {}
                        })();
                        """.trimIndent(),
                        null
                )
        }

    fun scheduleZoneWebRetry(view: WebView?, failedUrl: String?, reason: String?) {
        val targetUrl = failedUrl?.takeIf { it.isNotBlank() }
            ?: lastLoadedZoneDrmUrl?.takeIf { it.isNotBlank() }
            ?: zoneDrmStartupUrl.takeIf { it.isNotBlank() }
            ?: return
        if (view == null) return

        if (zoneWebRetryCount >= 8) {
            Log.e(TAG, "ZoneWeb retries exhausted. reason=$reason url=$targetUrl")
            isWebLoading = false
            return
        }

        zoneWebRetryCount += 1
        val retryDelayMs = (zoneWebRetryCount * 1200L).coerceAtMost(5_000L)
        Log.w(TAG, "ZoneWeb retry #$zoneWebRetryCount in ${retryDelayMs}ms. reason=$reason url=$targetUrl")
        zoneWebRetryJob?.cancel()
        zoneWebRetryJob = scope.launch {
            delay(retryDelayMs)
            try {
                view.stopLoading()
            } catch (_: Exception) {
            }
            try {
                isWebLoading = true
                view.loadUrl(targetUrl)
                lastLoadedZoneDrmUrl = targetUrl
            } catch (_: Exception) {
            }
        }
    }


    // Keep local state in sync when a new intent provides a different index.
    // Only update when a real index is given (>= 0). -1 means "play by URL" (e.g. plugin/Zee
    // channels) and must not be coerced to 0, which would wrongly play channelList[0].
    LaunchedEffect(currentChannelIndex) {
        if (currentChannelIndex >= 0 && currentChannelIndex != currentIndex) {
            currentIndex = currentChannelIndex
        }
    }

    // --- Epg fetch ---
    val epgCache = remember { mutableStateMapOf<String, Pair<Long, String?>>() }
    LaunchedEffect(showChannelPanel, channelList) {
        if (showChannelPanel && channelList != null) {
            while (showChannelPanel) {
                val visibleChannels = channelList
                withContext(Dispatchers.IO) {
                    visibleChannels.mapNotNull { channel ->
                        val channelId = extractChannelIdFromPlayUrl(channel.videoUrl)
                        if (channelId != null) {
                            async {
                                val now = System.currentTimeMillis()
                                val cached = epgCache[channelId]
                                if (cached == null || now - cached.first > 900_000) {
                                    val epgName = fetchCurrentProgram(basefinURL, channelId)
                                    epgCache[channelId] = now to epgName
                                }
                            }
                        } else null
                    }.awaitAll()
                }
                delay(900_000)
            }
        }
    }

    // --- Resize Config ---
    val resizeModes = remember {
        listOf(
            Triple(RESIZE_MODE_FIT, "Default", "DEF"),
            Triple(RESIZE_MODE_FILL, "Stretch", "STRETCH")
        )
    }
    var resizeModeIndex by remember { mutableIntStateOf(0) }
    var showResizeOverlay by remember { mutableStateOf(false) }
    var resizeOverlayLabel by remember { mutableStateOf(resizeModes.first().second) }
    var resizeOverlayJob by remember { mutableStateOf<Job?>(null) }
    var videoAspect by remember { mutableFloatStateOf(16f / 9f) }

    // --- Key Num Entry ---
    fun commitNumericEntryLocal(list: ArrayList<ChannelInfo>?) {
        val num = numericBuffer.toIntOrNull()
        if (num != null && !list.isNullOrEmpty()) {
            val idx = (num - 1).coerceIn(0, list.size - 1)
            currentIndex = idx
        }
        numericBuffer = ""
        showNumericOverlay = false
    }

    fun dispatchAndroidKeyToZoneWeb(action: Int, keyCode: Int): Boolean {
        val webView = zoneWebView ?: return false
        return try {
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true
            webView.requestFocus(View.FOCUS_DOWN)
            webView.dispatchKeyEvent(android.view.KeyEvent(action, keyCode))
            // Consume DPAD events once routed to web to avoid fall-through into non-web handlers.
            true
        } catch (_: Exception) {
            false
        }
    }

    fun scheduleControllerAutoHide() {
        if (PlayerCommandBus.isInPipMode) return
        controllerAutoHideJob?.cancel()
        controllerAutoHideJob = scope.launch {
            delay(if (isTv) 1_800 else 3_000)
            exoPlayerView?.hideController()
            isControllerVisible = false
            showChannelPanel = false
            showResizeOverlay = false
            showNumericOverlay = false
        }
    }

        fun showAndFocusZoneShakaController() {
                val webView = zoneWebView ?: return
                webView.evaluateJavascript(
                        """
                        (function() {
                            try {
                                var root = document.querySelector('.shaka-controls-container');
                                if (root) {
                                    root.classList.remove('shaka-hidden');
                                    root.style.opacity = '1';
                                }

                                function isVisible(el) {
                                    if (!el) return false;
                                    var style = window.getComputedStyle(el);
                                    if (!style) return false;
                                    if (style.display === 'none' || style.visibility === 'hidden') return false;
                                    if (parseFloat(style.opacity || '1') === 0) return false;
                                    var rect = el.getBoundingClientRect();
                                    return rect && rect.width > 0 && rect.height > 0;
                                }

                                function isExitLike(el) {
                                    var cls = String(el.className || '').toLowerCase();
                                    var label = String(el.getAttribute('aria-label') || el.getAttribute('title') || el.textContent || '').toLowerCase();
                                    return cls.indexOf('exit') >= 0 || cls.indexOf('close') >= 0 || label.indexOf('exit') >= 0 || label.indexOf('close') >= 0;
                                }

                                function isMuteLike(el) {
                                    var cls = String(el.className || '').toLowerCase();
                                    var label = String(el.getAttribute('aria-label') || el.getAttribute('title') || el.textContent || '').toLowerCase();
                                    return cls.indexOf('mute') >= 0 || cls.indexOf('volume') >= 0 || label.indexOf('mute') >= 0 || label.indexOf('volume') >= 0;
                                }

                                function isMoreLike(el) {
                                    var cls = String(el.className || '').toLowerCase();
                                    var label = String(el.getAttribute('aria-label') || el.getAttribute('title') || el.textContent || '').toLowerCase();
                                    return cls.indexOf('overflow') >= 0 || cls.indexOf('menu') >= 0 || cls.indexOf('more') >= 0 || cls.indexOf('setting') >= 0 ||
                                        label.indexOf('overflow') >= 0 || label.indexOf('menu') >= 0 || label.indexOf('more') >= 0 || label.indexOf('setting') >= 0 ||
                                        label.indexOf('option') >= 0;
                                }

                                var active = document.activeElement;
                                if (active && active.tagName === 'BUTTON' && !active.disabled && isVisible(active) && !isExitLike(active) && !isMuteLike(active) && isMoreLike(active)) {
                                    if (window.__zoneNotifyShakaController) window.__zoneNotifyShakaController();
                                    return true;
                                }

                                var candidates = [];
                                if (root) {
                                    candidates = Array.prototype.slice.call(root.querySelectorAll('button:not([disabled])'));
                                }

                                var preferred = null;
                                for (var i = 0; i < candidates.length; i++) {
                                    var el = candidates[i];
                                    if (!isVisible(el) || isExitLike(el) || isMuteLike(el)) continue;
                                    if (isMoreLike(el)) {
                                        preferred = el;
                                        break;
                                    }
                                }

                                if (!preferred) {
                                    for (var k = 0; k < candidates.length; k++) {
                                        var fallback = candidates[k];
                                        var fallbackCls = String(fallback.className || '').toLowerCase();
                                        if (!isVisible(fallback) || isExitLike(fallback) || isMuteLike(fallback)) continue;
                                        if (fallbackCls.indexOf('play') >= 0) {
                                            preferred = fallback;
                                            break;
                                        }
                                    }
                                }

                                if (!preferred) {
                                    for (var j = 0; j < candidates.length; j++) {
                                        if (isVisible(candidates[j]) && !isExitLike(candidates[j]) && !isMuteLike(candidates[j])) {
                                            preferred = candidates[j];
                                            break;
                                        }
                                    }
                                }

                                if (preferred) {
                                    preferred.focus();
                                }

                                if (window.__zoneNotifyShakaController) {
                                    window.__zoneNotifyShakaController();
                                }
                                return !!preferred;
                            } catch (e) {
                                return false;
                            }
                        })();
                        """.trimIndent(),
                        null
                )
                isZoneShakaControllerVisible = true
        }

        fun clickFocusedZoneShakaControllerItem() {
                val webView = zoneWebView ?: return
                webView.evaluateJavascript(
                        """
                        (function() {
                            try {
                                function isExitLike(el) {
                                    var cls = String(el.className || '').toLowerCase();
                                    var label = String(el.getAttribute('aria-label') || el.getAttribute('title') || el.textContent || '').toLowerCase();
                                    return cls.indexOf('exit') >= 0 || cls.indexOf('close') >= 0 || label.indexOf('exit') >= 0 || label.indexOf('close') >= 0;
                                }

                                var active = document.activeElement;
                                if (active && active.tagName === 'BUTTON' && !active.disabled && !isExitLike(active)) {
                                    active.click();
                                    if (window.__zoneNotifyShakaController) window.__zoneNotifyShakaController();
                                    return true;
                                }

                                if (window.__zoneNotifyShakaController) window.__zoneNotifyShakaController();
                                return false;
                            } catch (e) {
                                return false;
                            }
                        })();
                        """.trimIndent(),
                        null
                )
        }

    fun refreshShakaVisibilityAndOpenChannelPanelIfHidden() {
        val webView = zoneWebView ?: return
        webView.evaluateJavascript(
            """
            (function() {
              try {
                var root = document.querySelector('.shaka-controls-container');
                if (!root) return false;
                var style = window.getComputedStyle(root);
                if (!style) return false;
                if (root.classList.contains('shaka-hidden')) return false;
                if (style.display === 'none' || style.visibility === 'hidden') return false;
                if (parseFloat(style.opacity || '1') === 0) return false;

                                function isVisible(el) {
                                    if (!el) return false;
                                    var s = window.getComputedStyle(el);
                                    if (!s) return false;
                                    if (s.display === 'none' || s.visibility === 'hidden') return false;
                                    if (parseFloat(s.opacity || '1') === 0) return false;
                                    var r = el.getBoundingClientRect();
                                    return r && r.width > 0 && r.height > 0;
                                }

                                var buttons = Array.prototype.slice.call(root.querySelectorAll('button:not([disabled])'));
                                for (var i = 0; i < buttons.length; i++) {
                                    if (isVisible(buttons[i])) return true;
                                }
                                return false;
              } catch (e) {
                return false;
              }
            })();
            """.trimIndent()
        ) { result ->
            val visible = result?.contains("true", ignoreCase = true) == true
            isZoneShakaControllerVisible = visible
            if (!visible && !showChannelPanel) {
                panelSelectedIndex = currentIndex
                showChannelPanel = channelList != null
            }
        }
    }

    fun hideZoneShakaController() {
        zoneWebView?.evaluateJavascript(
            """
            (function() {
              try {
                var root = document.querySelector('.shaka-controls-container');
                if (root) {
                  root.classList.add('shaka-hidden');
                }
                if (window.__zoneNotifyShakaController) {
                  window.__zoneNotifyShakaController();
                }
              } catch (e) {}
            })();
            """.trimIndent(),
            null
        )
        isZoneShakaControllerVisible = false
    }

    fun dispatchDpadToZoneWeb(event: KeyEvent): Boolean {
        val keyCode = when (event.key) {
            Key.DirectionLeft -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            Key.DirectionRight -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            Key.DirectionUp -> android.view.KeyEvent.KEYCODE_DPAD_UP
            Key.DirectionDown -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> android.view.KeyEvent.KEYCODE_DPAD_CENTER
            else -> return false
        }

        val action = when (event.type) {
            KeyEventType.KeyDown -> android.view.KeyEvent.ACTION_DOWN
            KeyEventType.KeyUp -> android.view.KeyEvent.ACTION_UP
            else -> return false
        }

        return dispatchAndroidKeyToZoneWeb(action, keyCode)
    }

    val exoPlayer = remember {
        initializePlayer(
            getCurrentVideoUrl = { overrideVideoUrl ?: channelList?.getOrNull(currentIndex)?.videoUrl ?: videoUrl },
            context = context,
            retryCountRef = retryCountRef
        )
    }

    fun startPlaybackAfterViewAttach(playbackUrl: String, seekToPosition: Long = 0L) {
        val normalizedUrl = normalizePlaybackUrl(context, playbackUrl)
        if (normalizedUrl.isBlank()) return

        scope.launch {
            var attempts = 0
            while (exoPlayerView?.isAttachedToWindow != true && attempts < 80) {
                delay(25)
                attempts++
            }

            val mediaItem = buildMediaItemForPlaybackUrl(normalizedUrl)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            if (seekToPosition > 0L) {
                exoPlayer.seekTo(seekToPosition)
            }
            exoPlayer.playWhenReady = true
            setupCustomPlaybackLogic(exoPlayer, normalizedUrl)
        }
    }

    // --- Resize Config ---
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height != 0) {
                    val ar = videoSize.width.toFloat() / videoSize.height.toFloat()
                    if (ar.isFinite() && ar > 0.1f && ar < 10f) {
                        videoAspect = ar
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (useZoneDrmWebPlayer) {
                        zoneWebView?.onResume()
                    } else {
                        exoPlayer.playWhenReady = true
                    }
                }

                Lifecycle.Event.ON_PAUSE -> if (!PlayerCommandBus.isInPipMode && !PlayerCommandBus.isEnteringPip) {
                    if (useZoneDrmWebPlayer) {
                        zoneWebView?.onPause()
                    } else {
                        exoPlayer.playWhenReady = false
                    }
                }

                Lifecycle.Event.ON_DESTROY -> {
                    // Do not release here — onDispose always runs and is the
                    // single correct place to release. Releasing in both places
                    // causes a double-release which is undefined behaviour.
                    try {
                        exoPlayer.stop()
                        exoPlayer.clearMediaItems()
                    } catch (_: Exception) {
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            zoneWebRetryJob?.cancel()
            controllerAutoHideJob?.cancel()
            try {
                zoneWebView?.destroy()
            } catch (_: Exception) {
            }
            cleanupPlaybackLogic(exoPlayer)  // remove listener ref before release → no leak
            exoPlayer.release()
        }
    }

    // --- Custom Buffering ---
    var isBuffering by remember { mutableStateOf(false) }
    DisposableEffect(exoPlayer) {
        val bufferHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val showBufferingRunnable = Runnable { isBuffering = true }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) {
                    // Debounce: only show spinner if buffering persists > 1500ms.
                    // seekTo() and normal HLS segment fetches complete well within
                    // that window, so they never show the spinner at all.
                    bufferHandler.postDelayed(showBufferingRunnable, 1500)
                } else {
                    bufferHandler.removeCallbacks(showBufferingRunnable)
                    isBuffering = false
                }
                // Update PiP actions as play/pause state may have effectively changed
                PlayerCommandBus.notifyStateChanged()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                PlayerCommandBus.notifyStateChanged()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                PlayerCommandBus.notifyStateChanged()
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onPositionDiscontinuity(reason: Int) {
                // Seek or track changes can flip playing state; refresh PiP actions
                PlayerCommandBus.notifyStateChanged()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            bufferHandler.removeCallbacks(showBufferingRunnable)
            exoPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(lifecycleOwner.lifecycle.currentStateAsState().value) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(currentIndex) {
        overrideVideoUrl = null
        retryCountRef.value = 0
        // Do NOT set isBuffering=true here — let the 800ms debounce in the Player.Listener
        // handle it. Setting it immediately causes the spinner to flash on every channel
        // switch even when the new channel starts playing within milliseconds.
        try {
            val currentUrlRaw = channelList?.getOrNull(currentIndex)?.videoUrl ?: videoUrl
            val currentUrl = normalizePlaybackUrl(context, currentUrlRaw)
            if (currentUrl.isBlank()) {
                Toast.makeText(context, "Invalid stream URL", Toast.LENGTH_SHORT).show()
                try {
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    exoPlayer.playWhenReady = false
                } catch (_: Exception) {
                }
                return@LaunchedEffect
            }

            val isDrmRoute = isLikelyDrmRoute(currentUrlRaw) || isLikelyDrmRoute(currentUrl)
            if (isDrmRoute) {
                // DRM channels are rendered in embedded Shaka WebView to keep Zone UI shell.
                try {
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    exoPlayer.playWhenReady = false
                } catch (_: Exception) {
                }

                if (!PlayerCommandBus.isInPipMode) {
                    showChannelOverlay = true
                    delay(overlayDisplayTimeMs.toLong())
                    showChannelOverlay = false
                }
                return@LaunchedEffect
            }

            startPlaybackAfterViewAttach(currentUrl)
        } catch (_: Exception) {
            Toast.makeText(context, "Stream not supported", Toast.LENGTH_SHORT).show()
            try {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.playWhenReady = false
            } catch (_: Exception) {
            }
            return@LaunchedEffect
        }

        if (!PlayerCommandBus.isInPipMode) {
            showChannelOverlay = true
            delay(overlayDisplayTimeMs.toLong())
            showChannelOverlay = false
        }
    }

    // Expose player controls to PiP actions
    DisposableEffect(channelList) {
        PlayerCommandBus.setHandlers(
            playPause = {
                try {
                    if (useZoneDrmWebPlayer) {
                        zoneWebView?.evaluateJavascript(
                            """
                            (function() {
                              var v = document.querySelector('video');
                              if (!v) return;
                              if (v.paused) { v.play(); } else { v.pause(); }
                            })();
                            """.trimIndent(),
                            null
                        )
                    } else {
                        exoPlayer.playWhenReady = !exoPlayer.playWhenReady
                    }
                } finally {
                    PlayerCommandBus.notifyStateChanged()
                    // Some launchers refresh PiP actions a tick later; send a follow-up update
                    scope.launch {
                        delay(120)
                        PlayerCommandBus.notifyStateChanged()
                    }
                }
            },
            next = {
                channelList?.let {
                    if (it.isNotEmpty()) {
                        val ni = (currentIndex + 1) % it.size
                        overrideVideoUrl = null
                        currentIndex = ni
                        PlayerCommandBus.requestSwitch(index = ni)
                    }
                }
            },
            prev = {
                channelList?.let {
                    if (it.isNotEmpty()) {
                        val pi = if (currentIndex - 1 < 0) it.size - 1 else currentIndex - 1
                        overrideVideoUrl = null
                        currentIndex = pi
                        PlayerCommandBus.requestSwitch(index = pi)
                    }
                }
            },
            // Use ExoPlayer.isPlaying so PiP icon matches actual playback (pause shows Play icon, play shows Pause icon)
            isPlaying = { if (useZoneDrmWebPlayer) true else exoPlayer.isPlaying }
        )
        PlayerCommandBus.setOnStopPlayback {
            try {
                if (useZoneDrmWebPlayer) {
                    zoneWebView?.onPause()
                } else {
                    if (isTv) {
                        exoPlayer.playWhenReady = false
                        exoPlayer.pause()
                    } else {
                        exoPlayer.stop()
                        exoPlayer.clearMediaItems()
                        exoPlayer.playWhenReady = false
                    }
                }
            } catch (_: Exception) {
            }
        }
        onDispose {
            PlayerCommandBus.setOnStopPlayback(null)
            PlayerCommandBus.clearHandlers()
        }
    }

    // Respond to external switch requests (e.g., when a new channel is picked while in PiP)
    DisposableEffect(channelList) {
        PlayerCommandBus.setOnSwitchRequest { url, index ->
            try {
                var targetUrl: String? = null
                if (index != null && !channelList.isNullOrEmpty() && index in channelList.indices) {
                    // Index switch has priority
                    overrideVideoUrl = null
                    currentIndex = index
                    targetUrl = channelList[index].videoUrl
                } else if (!url.isNullOrEmpty()) {
                    val normalizedIncoming = normalizePlaybackUrl(context, url)
                    val incomingId = extractChannelIdFromPlayUrl(normalizedIncoming)
                    val foundIdx = if (incomingId != null && !channelList.isNullOrEmpty()) {
                        channelList.indexOfFirst {
                            val candidate = it.videoUrl ?: return@indexOfFirst false
                            extractChannelIdFromPlayUrl(normalizePlaybackUrl(context, candidate)) == incomingId
                        }
                    } else {
                        -1
                    }
                    if (foundIdx >= 0 && !channelList.isNullOrEmpty()) {
                        overrideVideoUrl = null
                        currentIndex = foundIdx
                        targetUrl = channelList[foundIdx].videoUrl
                    } else {
                        overrideVideoUrl = url
                        targetUrl = url
                    }
                }
                if (!targetUrl.isNullOrEmpty()) {
                    retryCountRef.value = 0
                    val finalUrl = normalizePlaybackUrl(context, targetUrl)
                    if (finalUrl.isBlank()) return@setOnSwitchRequest
                    val mediaItem = buildMediaItemForPlaybackUrl(finalUrl)
                    // Do NOT stop() — stop() blanks the surface. Set the new item
                    // directly; keepContentOnPlayerReset keeps the last frame
                    // visible until the new channel's first frame arrives.
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
            } catch (_: Exception) {
            }
        }
        onDispose { PlayerCommandBus.setOnSwitchRequest(null) }
    }

    LaunchedEffect(currentIndex) {
        val channelId =
            channelList?.getOrNull(currentIndex)?.videoUrl?.let { extractChannelIdFromPlayUrl(it) }
        currentProgramName = channelId?.let { epgCache[it]?.second }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(panelSelectedIndex, showChannelPanel) {
        if (showChannelPanel) {
            val safeIndex = panelSelectedIndex.coerceIn(0, (channelList?.size ?: 1) - 1)
            listState.animateScrollToItem(safeIndex)
        }
    }

    // Back-to-PiP: pressing Back enters PiP (YouTube-like); fallback to finish if PiP unsupported
    BackHandler {
        when {
            showChannelPanel -> {

                showChannelPanel = false
            }

            useZoneDrmWebPlayer && isZoneShakaControllerVisible -> {
                hideZoneShakaController()
            }

            isControllerVisible -> {

                exoPlayerView?.hideController()
            }

            else -> {
                val act = (context as? Activity)
                val pm = context.packageManager
                val supportsPip = try {
                    pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
                } catch (_: Exception) {
                    false
                }
                if (!isTv && supportsPip && pipEnabled) {
                    try {
                        PlayerCommandBus.isEnteringPip = true
                        val params = android.app.PictureInPictureParams.Builder()
                            .setAspectRatio(android.util.Rational(16, 9))
                            .build()
                        act?.enterPictureInPictureMode(params)
                    } catch (_: Exception) {
                        act?.finish()
                    }
                } else {
                    act?.finish()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val isOkKey =
                    event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (useZoneDrmWebPlayer && !showChannelPanel &&
                    (event.key == Key.Back || event.key == Key.Escape) &&
                    (event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp) &&
                    isZoneShakaControllerVisible
                ) {
                    hideZoneShakaController()
                    return@onPreviewKeyEvent true
                }

                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && tvNAV != "2" && !useZoneDrmWebPlayer) {
                    panelSelectedIndex = currentIndex
                    showChannelPanel = channelList != null
                    return@onPreviewKeyEvent true
                }

                if (event.type == KeyEventType.KeyDown) {
                    val digit = when (event.key) {
                        Key.Zero -> 0
                        Key.One -> 1
                        Key.Two -> 2
                        Key.Three -> 3
                        Key.Four -> 4
                        Key.Five -> 5
                        Key.Six -> 6
                        Key.Seven -> 7
                        Key.Eight -> 8
                        Key.Nine -> 9
                        else -> null
                    }
                    if (digit != null) {
                        if (!(numericBuffer.isEmpty() && digit == 0) && numericBuffer.length < 4) {
                            numericBuffer += digit.toString()
                            showNumericOverlay = true
                            numericJob?.cancel()
                            numericJob = scope.launch {
                                delay(1200)
                                commitNumericEntryLocal(channelList)
                            }
                        }
                        return@onPreviewKeyEvent true
                    }
                }

                if (useZoneDrmWebPlayer && !showChannelPanel) {
                    if (isOkKey && event.type == KeyEventType.KeyDown) {
                        if (!isZoneShakaControllerVisible) {
                            showAndFocusZoneShakaController()
                            suppressNextZoneCenterKeyUp = true
                            return@onPreviewKeyEvent true
                        }
                        // Controller already visible: keep current focus and wait for KeyUp selection.
                        return@onPreviewKeyEvent true
                    }

                    if (isOkKey && event.type == KeyEventType.KeyUp) {
                        if (suppressNextZoneCenterKeyUp) {
                            suppressNextZoneCenterKeyUp = false
                            return@onPreviewKeyEvent true
                        }
                        clickFocusedZoneShakaControllerItem()
                        return@onPreviewKeyEvent true
                    }

                    if (event.key == Key.DirectionLeft) {
                        if (event.type == KeyEventType.KeyDown) {
                            panelSelectedIndex = currentIndex
                            showChannelPanel = channelList != null
                        }
                        return@onPreviewKeyEvent true
                    }

                    val isDpadForWeb = when (event.key) {
                        Key.DirectionRight,
                        Key.DirectionUp,
                        Key.DirectionDown -> true
                        else -> false
                    }
                    if (isDpadForWeb && (event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp)) {
                        dispatchDpadToZoneWeb(event)
                        return@onPreviewKeyEvent true
                    }
                }

                if (event.type == KeyEventType.KeyUp && isOkKey) {
                    if (showChannelPanel) {
                        if (!channelList.isNullOrEmpty()) {
                            currentIndex = panelSelectedIndex.coerceIn(0, channelList.size - 1)
                            showChannelPanel = false
                        }
                        return@onPreviewKeyEvent true
                    }

                    if (useZoneDrmWebPlayer) {
                        return@onPreviewKeyEvent false
                    }
                    val androidKeyEvent = android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_UP,
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    )
                    val handled = exoPlayerView?.dispatchKeyEvent(androidKeyEvent) == true
                    return@onPreviewKeyEvent handled
                }

                if (event.type == KeyEventType.KeyUp) {
                    if (showChannelPanel && (event.key == Key.DirectionRight || event.key == Key.Back)) {
                        showChannelPanel = false
                        return@onPreviewKeyEvent true
                    }
                }

                if (showChannelPanel && event.type == KeyEventType.KeyUp) {
                    when (event.key) {
                        Key.DirectionUp -> {
                            if (!channelList.isNullOrEmpty()) {
                                panelSelectedIndex =
                                    (panelSelectedIndex - 1 + channelList.size) % channelList.size
                            }
                            return@onPreviewKeyEvent true
                        }

                        Key.DirectionDown -> {
                            if (!channelList.isNullOrEmpty()) {
                                panelSelectedIndex = (panelSelectedIndex + 1) % channelList.size
                            }
                            return@onPreviewKeyEvent true
                        }

                        else -> {}
                    }
                }

                return@onPreviewKeyEvent handleTVRemoteKey(
                    event = event,
                    tvNAV = tvNAV,
                    channelList = channelList,
                    currentIndexState = { currentIndex },
                    onChannelChange = { currentIndex = it }
                )
            }


    ) {
        val currentResizeMode = resizeModes[resizeModeIndex].first
        val isDefaultMode = resizeModeIndex == 0
        val aspectForModifier = when {
            isDefaultMode -> 16f / 9f
            currentResizeMode == RESIZE_MODE_FILL -> videoAspect
            else -> videoAspect
        }

        if (useZoneDrmWebPlayer) {
            AndroidView(
                factory = {
                    WebView(it).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.defaultTextEncodingName = "utf-8"
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isLongClickable = false
                        isHapticFeedbackEnabled = false
                        setOnLongClickListener { true }

                        webChromeClient = object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest?) {
                                if (request == null) return
                                (context as? Activity)?.runOnUiThread {
                                    try {
                                        request.grant(request.resources)
                                    } catch (_: Exception) {
                                        request.deny()
                                    }
                                }
                            }

                            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                                    consoleMessage.message().contains("drm", ignoreCase = true) ||
                                    consoleMessage.message().contains("widevine", ignoreCase = true)
                                ) {
                                    Log.e(
                                        TAG,
                                        "ZoneWeb ${consoleMessage.messageLevel()}: ${consoleMessage.message()} @${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                                    )
                                }
                                return super.onConsoleMessage(consoleMessage)
                            }
                        }

                        addJavascriptInterface(
                            ZoneShakaBridge { visible ->
                                Handler(Looper.getMainLooper()).post {
                                    isZoneShakaControllerVisible = visible
                                }
                            },
                            "ZoneShakaBridge"
                        )

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                isWebLoading = true
                                isZoneShakaControllerVisible = false
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: android.webkit.WebResourceError
                            ) {
                                if (!request.isForMainFrame) return
                                isWebLoading = true
                                scheduleZoneWebRetry(
                                    view = view,
                                    failedUrl = request.url?.toString(),
                                    reason = error.description?.toString()
                                )
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                errorResponse: WebResourceResponse
                            ) {
                                if (!request.isForMainFrame) return
                                if (errorResponse.statusCode >= 500) {
                                    isWebLoading = true
                                    scheduleZoneWebRetry(
                                        view = view,
                                        failedUrl = request.url?.toString(),
                                        reason = "HTTP ${errorResponse.statusCode}"
                                    )
                                }
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                isWebLoading = false
                                zoneWebRetryCount = 0
                                zoneWebRetryJob?.cancel()
                                zoneWebRetryJob = null
                                view.evaluateJavascript(
                                    """
                                    (function() {
                                        try {
                                            if (document && document.body) {
                                                if (document.body.tabIndex < 0) {
                                                    document.body.tabIndex = 0;
                                                }
                                                document.body.focus();
                                            }
                                        } catch (e) {}
                                    })();
                                    """.trimIndent(),
                                    null
                                )
                                // Do not force DOM/CSS in Shaka pages; aggressive overrides can hide video while audio continues.
                                view.evaluateJavascript(
                                    """
                                    (function() {
                                      try {
                                        var ids = ['zone-shaka-layout-fix', 'zone-shaka-hide-ui', 'zone-shell-hide-ui'];
                                        for (var i = 0; i < ids.length; i++) {
                                          var el = document.getElementById(ids[i]);
                                          if (el && el.parentNode) el.parentNode.removeChild(el);
                                        }
                                        window.__zoneShakaUIInstalled = false;
                                        window.__zoneShellUiObserverInstalled = false;
                                      } catch (e) {}
                                    })();
                                    """.trimIndent(),
                                    null
                                )

                                                                view.evaluateJavascript(
                                                                        """
                                                                        (function() {
                                                                            try {
                                                                                function viewportHeightPx() {
                                                                                    if (window.visualViewport && window.visualViewport.height) {
                                                                                        return Math.round(window.visualViewport.height);
                                                                                    }
                                                                                    return Math.round(window.innerHeight || document.documentElement.clientHeight || 0);
                                                                                }

                                                                                function applyViewportFix() {
                                                                                    var vh = viewportHeightPx();
                                                                                    if (!vh) return;
                                                                                    var px = vh + 'px';

                                                                                    var html = document.documentElement;
                                                                                    var body = document.body;
                                                                                    if (!html || !body) return;

                                                                                    html.style.height = px;
                                                                                    html.style.minHeight = px;
                                                                                    html.style.maxHeight = px;
                                                                                    html.style.overflow = 'hidden';

                                                                                    body.style.height = px;
                                                                                    body.style.minHeight = px;
                                                                                    body.style.maxHeight = px;
                                                                                    body.style.margin = '0';
                                                                                    body.style.padding = '0';
                                                                                    body.style.overflow = 'hidden';
                                                                                    body.style.background = 'black';

                                                                                    var selectors = [
                                                                                        '.shaka-video-container',
                                                                                        '.shaka-player-container',
                                                                                        '.player',
                                                                                        '.video-container',
                                                                                        '#player',
                                                                                        'main',
                                                                                        'iframe'
                                                                                    ];
                                                                                    selectors.forEach(function(sel) {
                                                                                        document.querySelectorAll(sel).forEach(function(el) {
                                                                                            el.style.height = px;
                                                                                            el.style.minHeight = px;
                                                                                            el.style.maxHeight = px;
                                                                                            el.style.width = '100%';
                                                                                            el.style.maxWidth = '100%';
                                                                                            el.style.overflow = 'hidden';
                                                                                        });
                                                                                    });

                                                                                    var v = document.querySelector('video');
                                                                                    if (v) {
                                                                                        v.style.width = '100%';
                                                                                        v.style.height = '100%';
                                                                                        v.style.objectFit = 'contain';
                                                                                        v.style.objectPosition = 'center center';
                                                                                        v.style.display = 'block';
                                                                                        v.style.margin = '0 auto';
                                                                                        v.style.background = 'black';
                                                                                    }
                                                                                }

                                                                                window.__zoneApplyViewportFix = applyViewportFix;

                                                                                if (!window.__zoneViewportFixInstalled) {
                                                                                    window.__zoneViewportFixInstalled = true;
                                                                                    window.addEventListener('resize', applyViewportFix, true);
                                                                                    window.addEventListener('orientationchange', function() {
                                                                                        setTimeout(applyViewportFix, 60);
                                                                                        setTimeout(applyViewportFix, 220);
                                                                                    }, true);
                                                                                    if (window.visualViewport) {
                                                                                        window.visualViewport.addEventListener('resize', applyViewportFix, true);
                                                                                        window.visualViewport.addEventListener('scroll', applyViewportFix, true);
                                                                                    }
                                                                                }

                                                                                setTimeout(applyViewportFix, 0);
                                                                                setTimeout(applyViewportFix, 120);
                                                                                setTimeout(applyViewportFix, 320);
                                                                            } catch (e) {}
                                                                        })();
                                                                        """.trimIndent(),
                                                                        null
                                                                )

                                                                view.evaluateJavascript(
                                                                        """
                                                                        (function() {
                                                                            try {
                                                                                function controlsVisible() {
                                                                                    var root = document.querySelector('.shaka-controls-container');
                                                                                    if (!root) return false;
                                                                                    var style = window.getComputedStyle(root);
                                                                                    if (!style) return false;
                                                                                    if (root.classList.contains('shaka-hidden')) return false;
                                                                                    if (style.display === 'none' || style.visibility === 'hidden') return false;
                                                                                    if (parseFloat(style.opacity || '1') === 0) return false;
                                                                                    return true;
                                                                                }

                                                                                function notify() {
                                                                                    var visible = controlsVisible();
                                                                                    if (window.ZoneShakaBridge && window.ZoneShakaBridge.onControllerVisibilityChanged) {
                                                                                        window.ZoneShakaBridge.onControllerVisibilityChanged(visible);
                                                                                    }
                                                                                }

                                                                                window.__zoneNotifyShakaController = notify;
                                                                                window.__zoneScheduleShakaAutoHide = null;

                                                                                if (!window.__zoneShakaControllerObserverInstalled) {
                                                                                    window.__zoneShakaControllerObserverInstalled = true;
                                                                                    var target = document.querySelector('.shaka-controls-container') || document.body;
                                                                                    if (target) {
                                                                                        var observer = new MutationObserver(function() { notify(); });
                                                                                        observer.observe(target, {
                                                                                            attributes: true,
                                                                                            childList: true,
                                                                                            subtree: true,
                                                                                            attributeFilter: ['class', 'style']
                                                                                        });
                                                                                    }
                                                                                    ['keydown','keyup','click','focusin','mousemove','touchstart'].forEach(function(evt) {
                                                                                        document.addEventListener(evt, function() {
                                                                                            setTimeout(notify, 0);
                                                                                        }, true);
                                                                                    });
                                                                                }

                                                                                setTimeout(notify, 100);
                                                                                setTimeout(notify, 400);
                                                                            } catch (e) {}
                                                                        })();
                                                                        """.trimIndent(),
                                                                        null
                                                                )

                                // Some backend failures are rendered as plain text pages. Detect and auto-retry.
                                view.evaluateJavascript(
                                    """
                                    (function() {
                                      try {
                                        return (document && document.body && document.body.innerText) ? document.body.innerText : '';
                                      } catch (e) { return ''; }
                                    })();
                                    """.trimIndent()
                                ) { pageText ->
                                    val text = pageText.orEmpty().lowercase()
                                    if (text.contains("network is unreachable") || text.contains("dial tcp")) {
                                        scheduleZoneWebRetry(
                                            view = view,
                                            failedUrl = url,
                                            reason = "upstream network unreachable"
                                        )
                                    }
                                }
                            }
                        }
                        zoneWebView = this
                        if (zoneDrmStartupUrl.isNotBlank()) {
                            loadUrl(zoneDrmStartupUrl)
                            lastLoadedZoneDrmUrl = zoneDrmStartupUrl
                        }
                    }
                },
                update = { webView ->
                    zoneWebView = webView
                    webView.isFocusable = true
                    webView.isFocusableInTouchMode = true
                    if (zoneDrmStartupUrl.isNotBlank() && zoneDrmStartupUrl != lastLoadedZoneDrmUrl) {
                        webView.loadUrl(zoneDrmStartupUrl)
                        lastLoadedZoneDrmUrl = zoneDrmStartupUrl
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center)
            )
        } else AndroidView(
            factory = {
                PlayerView(it).apply {
                    setEnableComposeSurfaceSyncWorkaround(true)
                    useController = false
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    // Keep the last rendered frame frozen on screen during buffering,
                    // retries, and player resets — this is the primary fix for black screens.
                    setKeepContentOnPlayerReset(true)
                    setResizeMode(resizeModes[resizeModeIndex].first)
                    player = exoPlayer
                    exoPlayerView = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center)
        )

        if ((isBuffering && !useZoneDrmWebPlayer) || (isWebLoading && useZoneDrmWebPlayer)) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(64.dp),
                    stroke = Stroke(width = 4.dp.toPx()),
                    gapSize = 8.dp,
                    amplitude = 0.75f,
                )
            }
        }

        if (useZoneDrmWebPlayer) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                AnimatedVisibility(
                    visible = !PlayerCommandBus.isInPipMode && (isTv || isZoneShakaControllerVisible || showChannelPanel),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                showChannelPanel = channelList != null && !showChannelPanel
                                panelSelectedIndex = currentIndex.coerceAtLeast(0)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesomeMotion,
                                contentDescription = "Channels",
                                tint = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }
        }

        // When PiP mode changes, immediately hide controllers/overlays
        DisposableEffect(Unit) {
            PlayerCommandBus.setOnPipModeChanged { isPip ->
                if (isPip) {
                    try {
                        showChannelPanel = false
                        isControllerVisible = false
                        exoPlayerView?.useController = false
                        exoPlayerView?.hideController()
                    } catch (_: Exception) {
                    }
                } else {
                    try {
                        exoPlayerView?.useController = true
                    } catch (_: Exception) {
                    }
                }
            }
            onDispose { PlayerCommandBus.setOnPipModeChanged(null) }
        }

        LaunchedEffect(showChannelPanel) {
            if (showChannelPanel) {
                focusRequester.requestFocus()
            }
        }

        LaunchedEffect(showChannelPanel, showResizeOverlay, showNumericOverlay) {
            if (showChannelPanel || showResizeOverlay || showNumericOverlay) {
                scheduleControllerAutoHide()
            }
        }

        AnimatedVisibility(
            visible = !PlayerCommandBus.isInPipMode && (showChannelOverlay && channelList != null),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            ChannelInfoOverlay(
                channelList = channelList,
                currentIndex = currentIndex,
                currentProgramName = currentProgramName
            )
            CurrentTimeOverlay(
                visible = !PlayerCommandBus.isInPipMode && (showChannelOverlay && channelList != null)
            )
        }

        // --- Key Num Config ---
        if (!PlayerCommandBus.isInPipMode && showNumericOverlay && numericBuffer.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = numericBuffer,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
            }
        }

        if (showResizeOverlay) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 90.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = resizeOverlayLabel,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }
        }

        // Left-side channel panel
        if (showChannelPanel && channelList != null) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .width(280.dp)
                    .background(Color.Black.copy(alpha = 0.8f))
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 12.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(
                        items = channelList,
                        key = { idx, _ -> idx }
                    ) { idx, ch ->
                        val isSelected = idx == panelSelectedIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) Color(0x33FFFFFF) else Color.Transparent)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Transparent)
                                .clickable {
                                    panelSelectedIndex = idx
                                    currentIndex = idx
                                    showChannelPanel = false
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(ch.logoUrl)
                                    .size(80)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Logo",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )

                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = String.format("%02d", idx + 1),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(40.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))

                            val channelId = extractChannelIdFromPlayUrl(ch.videoUrl)
                            Column {
                                Text(
                                    text = ch.channelName,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    maxLines = 1
                                )
                                EpgText(channelId, epgCache)
                            }
                        }
                    }
                }
            }
        }
    }

    // Also handle direct video URL changes (e.g., new intent while in PiP with no channel list/index)
    LaunchedEffect(videoUrl) {
        if (channelList.isNullOrEmpty() || currentIndex < 0) {
            val rawUrl = channelList?.getOrNull(currentIndex)?.videoUrl ?: videoUrl
            val url = normalizePlaybackUrl(context, rawUrl)
            if (url.isBlank()) return@LaunchedEffect
            val isDrmRoute = isLikelyDrmRoute(rawUrl) || isLikelyDrmRoute(url)
            if (isDrmRoute) return@LaunchedEffect
            startPlaybackAfterViewAttach(url)
        }
    }

}

@Composable
fun Dp.toPx(): Float = with(LocalDensity.current) { this@toPx.toPx() }

@Composable
fun EpgText(
    channelId: String?,
    epgCache: Map<String, Pair<Long, String?>>
) {
    val epg = channelId?.let { epgCache[it]?.second }
    if (!epg.isNullOrBlank()) {
        Text(
            text = epg,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            maxLines = 1
        )
    }
}


suspend fun fetchCurrentProgram(basefinURL: String, channelId: String): String? {
    val epgURLc = "$basefinURL/epg/${channelId}/0"
//    Log.d("NANOdix1", epgURLc)
    val epgData = ChannelUtils.fetchEpg(epgURLc)
//    Log.d(TAG, "Now playing: ${epgData?.showname}")
    return epgData?.showname
}

@SuppressLint("DefaultLocale")
@Composable
fun ChannelInfoOverlay(
    channelList: List<ChannelInfo>?,
    currentIndex: Int,
    currentProgramName: String?
) {
    channelList?.getOrNull(currentIndex)?.let { channel ->
        Box(modifier = Modifier.fillMaxSize()) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.75f))
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Logo
                        Card(
                            modifier = Modifier.size(60.dp),
                            shape = RoundedCornerShape(16.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color.DarkGray.copy(
                                    alpha = 0.5f
                                )
                            )
                        ) {
                            AsyncImage(
                                model = channel.logoUrl,
                                contentDescription = "Channel Logo",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(60.dp)
                                    .shadow(8.dp, RoundedCornerShape(16.dp), clip = false)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White.copy(alpha = 0.8f))
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            // Channel Number + Name
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = String.format("%02d", currentIndex + 1),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier
                                        .padding(end = 12.dp)
                                        .widthIn(min = 36.dp)
                                )
                                Text(
                                    text = channel.channelName,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                    maxLines = 1
                                )
                            }

                            currentProgramName?.let { programName ->
                                Spacer(modifier = Modifier.height(0.dp))
                                Text(
                                    text = programName,
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CurrentTimeOverlay(visible: Boolean) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    AnimatedVisibility(
        visible = visible && isLandscape,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.50f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                var currentTime by remember { mutableStateOf(getCurrentFormattedTime()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        currentTime = getCurrentFormattedTime()
                        delay(60000L)
                    }
                }
                Text(
                    text = currentTime,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp
                )
            }
        }
    }
}

@SuppressLint("DefaultLocale")
fun getCurrentFormattedTime(): String {
    val cal = Calendar.getInstance()
    val hour = cal.get(Calendar.HOUR)
    val minute = cal.get(Calendar.MINUTE)
    val amPm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
    return String.format("%02d:%02d %s", if (hour == 0) 12 else hour, minute, amPm)
}

private fun buildMediaItemForPlaybackUrl(url: String): MediaItem {
    val builder = MediaItem.Builder().setUri(url.toUri())
    val mimeType = inferPlaybackMimeType(url)
    if (!mimeType.isNullOrBlank()) builder.setMimeType(mimeType)

    // For HLS live streams: stay 15 s behind the live edge.
    // ExoPlayer silently pre-fetches the next 15 s of segments in the background,
    // so any network hiccup shorter than 15 s is absorbed with zero stall or black
    // screen. Playback may start 15 s delayed (acceptable for TV live streams).
    val isLikelyLive = mimeType == MimeTypes.APPLICATION_M3U8 || mimeType == null
    if (isLikelyLive) {
        builder.setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(15_000)   // play 15 s behind live edge
                .setMinOffsetMs(8_000)       // never closer than 8 s to live edge
                .setMaxOffsetMs(25_000)      // never further than 25 s behind
                .setMinPlaybackSpeed(0.97f)  // allow slight slowdown to hold offset
                .setMaxPlaybackSpeed(1.03f)  // allow slight speedup to catch up
                .build()
        )
    }

    return builder.build()
}

private fun inferPlaybackMimeType(url: String): String? {
    val cleaned = url.substringBefore('#').substringBefore('?').lowercase()
    return when {
        cleaned.endsWith(".m3u8") || cleaned.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
        cleaned.endsWith(".mpd") || cleaned.contains(".mpd") -> MimeTypes.APPLICATION_MPD
        // Plugin channels (e.g. /zee5/...) served by the local server have no file extension.
        // All local-server endpoints ultimately serve HLS, so hint ExoPlayer accordingly.
        cleaned.contains("localhost") && !cleaned.substringAfterLast("/").contains(".") -> MimeTypes.APPLICATION_M3U8
        else -> null
    }
}

private fun isLikelyDrmRoute(url: String): Boolean {
    val u = url.lowercase()
    return u.contains("/play/") ||
            u.contains("/mpd/") ||
            u.contains(".mpd") ||
            u.contains("widevine") ||
            u.contains("render.dash")
}

private fun toZoneDrmUrl(inputUrl: String, localPort: Int, quality: String?): String {
    val cleaned = inputUrl.trim()
    if (cleaned.isBlank()) return cleaned
    if (cleaned.contains("/mpd/", ignoreCase = true) || cleaned.contains(".mpd", ignoreCase = true)) {
        return cleaned
    }

    val playRegex = Regex(".*/play/(\\d+)(?:[/?].*)?$")
    val match = playRegex.find(cleaned) ?: return cleaned
    val id = match.groupValues.getOrNull(1).orEmpty()
    if (id.isBlank()) return cleaned

    val normalizedQuality = quality?.trim()?.lowercase()
    val q = when (normalizedQuality) {
        "auto", "low", "medium", "high" -> normalizedQuality
        else -> "auto"
    }
    return "http://localhost:$localPort/mpd/$id?q=$q&pm=hd"
}

@UnstableApi
fun initializePlayer(
    getCurrentVideoUrl: () -> String,
    context: Context,
    retryCountRef: MutableState<Int>
): ExoPlayer {
    // Short timeouts: if a segment fetch hangs, fail fast (8 s) and retry
    // instead of waiting the OS default ~15 s with a black screen.
    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(8_000)
        .setReadTimeoutMs(8_000)
//        .setUserAgent(userAgent) //Future Ref
    val mediaSourceFactory =
            DefaultMediaSourceFactory(context)
                .setDataSourceFactory(httpDataSourceFactory)
                // Pre-fetch segments 15 s ahead of playback position at the factory level
                // (complements the per-MediaItem live configuration below).
                .setLiveTargetOffsetMs(15_000)

    // Buffer tuning for smooth live-stream playback:
    //   minBufferMs  20 s  – start refilling as soon as ahead-buffer < 20 s
    //   maxBufferMs  60 s  – keep up to 60 s downloaded ahead
    //   bufferForPlaybackMs  2.5 s  – fast cold start
    //   bufferForPlaybackAfterRebufferMs  6 s  – after a stall, wait for 6 s
    //       before resuming so we don't stutter again 2 s later
    //   setPrioritizeTimeOverSizeThresholds  – buffer is measured in time not
    //       bytes, so behaviour is consistent across bitrate switches
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs                     */ 14_000,
            /* maxBufferMs                     */ 45_000,
            /* bufferForPlaybackMs             */ 1_200,
            /* bufferForPlaybackAfterRebufferMs */ 1_200
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    val renderersFactory = DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)

    val player = ExoPlayer.Builder(context, renderersFactory)
        .setMediaSourceFactory(mediaSourceFactory)
        .setLoadControl(loadControl)
        .build()
    retryCountRef.value = 0
    val retryHandler = Handler(Looper.getMainLooper())
    val stallHandler = Handler(Looper.getMainLooper())
    val blackFrameHandler = Handler(Looper.getMainLooper())
    val fastStallThresholdMs = 5_000L
    val blackFrameThresholdMs = 6_500L
    var stallRecoveries = 0
    val maxStallRecoveries = 4
    var hasRenderedVideoFrame = false
    var blackScreenRecoveries = 0
    var lastBlackScreenRecoveryAt = 0L
    var lastRecoveryAt = 0L

    fun shouldRecover(minGapMs: Long = 3_500L): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecoveryAt < minGapMs) return false
        lastRecoveryAt = now
        return true
    }

    fun prepareAndPlay(seekToPosition: Long = 0L) {
        val normalizedUrl = normalizePlaybackUrl(context, getCurrentVideoUrl())
        if (normalizedUrl.isBlank()) return
        val mediaItem = buildMediaItemForPlaybackUrl(normalizedUrl)
        player.setMediaItem(mediaItem)
        player.prepare()
        if (seekToPosition > 0L) {
            player.seekTo(seekToPosition)
        }
        player.playWhenReady = true
    }

    // Always Retry
    player.addListener(object : Player.Listener {
        private var isBufferingLong = false
        private val bufferingStallRunnable = Runnable {
            if (!player.playWhenReady || player.playbackState != Player.STATE_BUFFERING) return@Runnable
            isBufferingLong = true
            if (stallRecoveries >= maxStallRecoveries) return@Runnable
            if (!shouldRecover()) return@Runnable
            stallRecoveries += 1

            val currentUrl = getCurrentVideoUrl()
            try {
                // Exo can get stuck in STATE_BUFFERING without emitting onPlayerError.
                // Re-preparing the current media item forces a playlist refresh and
                // usually recovers from token/segment expiry stalls.
                if (currentUrl.containsAnyId()) {
                    prepareAndPlay(player.currentPosition)
                } else {
                    prepareAndPlay()
                }
            } catch (_: Exception) {
            }
        }

        private val blackFrameRunnable = Runnable {
            if (!player.playWhenReady || player.playbackState != Player.STATE_READY) return@Runnable
            if (hasRenderedVideoFrame) return@Runnable
            if (player.videoFormat == null) return@Runnable
            if (blackScreenRecoveries >= 3) return@Runnable

            val now = SystemClock.elapsedRealtime()
            if (now - lastBlackScreenRecoveryAt < 2_500L) return@Runnable
            if (!shouldRecover()) return@Runnable
            lastBlackScreenRecoveryAt = now
            blackScreenRecoveries += 1

            try {
                // Recovery for TV devices where audio starts but video renderer gets stuck.
                prepareAndPlay(player.currentPosition)
            } catch (_: Exception) {
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val attempt = retryCountRef.value + 1
            retryCountRef.value = attempt
            if (attempt % 10 == 0) {
                Log.w(TAG, "Playback still failing after $attempt attempts: ${error.errorCodeName}")
            }
            val currentUrl = getCurrentVideoUrl()
            val retryDelayMs = (attempt.coerceAtMost(20) * 250L).coerceAtMost(3_000L)
            retryHandler.removeCallbacksAndMessages(null)
            retryHandler.postDelayed(
                {
                    if (!shouldRecover(2_000L)) return@postDelayed
                    try {
                        // Do NOT call player.stop() — stop() clears the video surface
                        // causing a black screen. Instead, set the media item and call
                        // prepare() directly; the last rendered frame stays visible on
                        // screen until new video frames arrive.
                        if (currentUrl.containsAnyId()) {
                            prepareAndPlay(player.currentPosition)
                        } else {
                            prepareAndPlay()
                        }
                    } catch (_: Exception) {
                    }
                },
                retryDelayMs
            )
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    blackFrameHandler.removeCallbacks(blackFrameRunnable)
                    if (player.playWhenReady) {
                        stallHandler.removeCallbacks(bufferingStallRunnable)
                        stallHandler.postDelayed(bufferingStallRunnable, fastStallThresholdMs)
                    }
                }

                Player.STATE_READY -> {
                    isBufferingLong = false
                    stallRecoveries = 0
                    retryCountRef.value = 0
                    stallHandler.removeCallbacks(bufferingStallRunnable)
                    blackFrameHandler.removeCallbacks(blackFrameRunnable)
                    blackFrameHandler.postDelayed(blackFrameRunnable, blackFrameThresholdMs)
                }

                Player.STATE_IDLE, Player.STATE_ENDED -> {
                    stallHandler.removeCallbacks(bufferingStallRunnable)
                    blackFrameHandler.removeCallbacks(blackFrameRunnable)
                }
            }
        }

        override fun onRenderedFirstFrame() {
            hasRenderedVideoFrame = true
            blackScreenRecoveries = 0
            blackFrameHandler.removeCallbacks(blackFrameRunnable)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                stallHandler.removeCallbacks(bufferingStallRunnable)
                if (!hasRenderedVideoFrame) {
                    blackFrameHandler.removeCallbacks(blackFrameRunnable)
                    blackFrameHandler.postDelayed(blackFrameRunnable, blackFrameThresholdMs)
                }
            } else if (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING && !isBufferingLong) {
                stallHandler.removeCallbacks(bufferingStallRunnable)
                stallHandler.postDelayed(bufferingStallRunnable, fastStallThresholdMs)
            }
        }
    })


    return player
}

fun handleTVRemoteKey(
    event: KeyEvent,
    tvNAV: String?,
    channelList: ArrayList<ChannelInfo>?,
    currentIndexState: () -> Int,
    onChannelChange: (Int) -> Unit
): Boolean {
    if (event.type != KeyEventType.KeyUp) return false
    val total = channelList?.size ?: 0
    if (total <= 0) return false

    val currentIndex = currentIndexState()
    val newIndex = when (tvNAV) {
        "0" -> when (event.key) {
            Key.ChannelUp -> (currentIndex + 1) % total
            Key.ChannelDown -> if (currentIndex - 1 < 0) total - 1 else currentIndex - 1

            else -> return false
        }

        "1" -> when (event.key) {
            Key.DirectionUp -> (currentIndex + 1) % total
            Key.DirectionDown -> if (currentIndex - 1 < 0) total - 1 else currentIndex - 1

            else -> return false
        }

        "2" -> when (event.key) {
            Key.DirectionRight -> (currentIndex + 1) % total
            Key.DirectionLeft -> if (currentIndex - 1 < 0) total - 1 else currentIndex - 1

            else -> return false
        }

        else -> return false
    }

    onChannelChange(newIndex)
    return true
}

private fun hookExoControllerButtons(playerView: PlayerView, context: Context) {
    playerView.post {
        try {
            val controller = playerView.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_controller) ?: return@post



        } catch (e: Exception) {
            Log.e("ExoCustom", "Failed to hook Exo buttons: ${e.message}")
        }
    }
}
