package com.skylake.skytv.jgorunner.utils

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.skylake.skytv.jgorunner.data.SkySharedPref
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@Composable
fun RememberBackPressManager(
    timeoutMs: Long = 2000L,
    onExit: () -> Unit,
    showHint: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()
    var lastPress by remember { mutableLongStateOf(0L) }
    var active by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        val now = System.currentTimeMillis()
        if (active && now - lastPress < timeoutMs) {
            onExit()
        } else {
            active = true
            lastPress = now
            scope.launch { showHint() }
            scope.launch {
                delay(timeoutMs)
                if (System.currentTimeMillis() - lastPress >= timeoutMs) {
                    active = false
                }
            }
        }
    }

    LaunchedEffect(Unit) { active = false }
}

@Composable
fun HandleTvBackKey(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK &&
                    keyEvent.type == KeyEventType.KeyUp
                ) {
                    onBack()
                    true
                } else {
                    false
                }
            }
    )
}

object DeviceUtils {
    fun isTvDevice(context: Context): Boolean {
        val pm: PackageManager = context.packageManager
        return try {
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        } catch (_: Exception) {
            false
        }
    }

    fun pendingIntentFlags(baseFlags: Int = 0): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            baseFlags or PendingIntent.FLAG_IMMUTABLE
        } else baseFlags
    }
}


fun withQuality(context: Context, chURL: String, logIT: Boolean = false): String {
    val skyPREF = SkySharedPref.getInstance(context).myPrefs
    var videoUrl = chURL

    logIT.takeIf { it }?.let { Log.d("wQTY", "input = $videoUrl") }
    when (skyPREF.filterQX?.lowercase()) {
        "low" -> videoUrl = videoUrl.replace("/live/", "/live/low/")
        "high" -> videoUrl = videoUrl.replace("/live/", "/live/high/")
        "medium" -> videoUrl = videoUrl.replace("/live/", "/live/medium/")
    }
    logIT.takeIf { it }?.let { Log.d("wQTY", "output = $videoUrl") }

    return videoUrl
}

fun normalizePlaybackUrl(context: Context, inputUrl: String): String {
    val skyPref = SkySharedPref.getInstance(context).myPrefs
    var url = inputUrl.trim().trim('`', '"', '\'')

    if (url.isEmpty()) return url

    val parsedForScheme = runCatching { Uri.parse(url) }.getOrNull()
    if (parsedForScheme != null && parsedForScheme.scheme.isNullOrEmpty()) {
        val base = "http://localhost:${skyPref.jtvGoServerPort}"
        url = if (url.startsWith("/")) "$base$url" else "$base/$url"
    }

    val parsedForPlay = runCatching { Uri.parse(url) }.getOrNull()
    val playPath = parsedForPlay?.encodedPath.orEmpty()
    val playMatch = Regex(""".*/play/(\d+)$""").find(playPath)
    if (playMatch != null) {
        val id = playMatch.groupValues[1]
        val livePath = "/live/$id.m3u8"
        url = parsedForPlay?.buildUpon()?.encodedPath(livePath)?.build()?.toString() ?: url
    }

    val parsed = runCatching { Uri.parse(url) }.getOrNull()
    val qFromPref = skyPref.filterQX?.trim()?.lowercase()
    val effectiveQuality = when (qFromPref) {
        "low", "medium", "high" -> qFromPref
        else -> null
    }

    if (parsed != null && parsed.query?.isNotEmpty() == true) {
        val builder = parsed.buildUpon().clearQuery()
        val names = runCatching { parsed.queryParameterNames }.getOrNull().orEmpty()
        for (name in names) {
            if (name.equals("q", ignoreCase = true)) continue
            val values = parsed.getQueryParameters(name)
            if (values.isEmpty()) {
                builder.appendQueryParameter(name, null)
            } else {
                values.forEach { v -> builder.appendQueryParameter(name, v) }
            }
        }
        url = builder.build().toString()
    }

    if (!effectiveQuality.isNullOrEmpty()) {
        val normalizedBase = url
            .replace("/live/low/", "/live/", ignoreCase = true)
            .replace("/live/medium/", "/live/", ignoreCase = true)
            .replace("/live/high/", "/live/", ignoreCase = true)
        url = when (effectiveQuality) {
            "low" -> normalizedBase.replace("/live/", "/live/low/", ignoreCase = true)
            "high" -> normalizedBase.replace("/live/", "/live/high/", ignoreCase = true)
            "medium" -> normalizedBase.replace("/live/", "/live/medium/", ignoreCase = true)
            else -> normalizedBase
        }
    }

    val parsedAfterQuality = runCatching { Uri.parse(url) }.getOrNull()
    val path = parsedAfterQuality?.encodedPath.orEmpty()
    // Fixed: Don't append .m3u8 if it's already an MPD or DASH stream
    if (path.contains("/live/", ignoreCase = true) &&
        !path.endsWith(".m3u8", ignoreCase = true) &&
        !path.endsWith(".m3u", ignoreCase = true) &&
        !path.endsWith(".mpd", ignoreCase = true) &&
        !path.endsWith(".dash", ignoreCase = true)
    ) {
        val newPath = if (path.endsWith("/")) path.dropLast(1) + ".m3u8" else "$path.m3u8"
        url = parsedAfterQuality?.buildUpon()?.encodedPath(newPath)?.build()?.toString() ?: url
    }

    url = url.replace(".m3u8.m3u8", ".m3u8", ignoreCase = true)
    url = url.replace("/.m3u8", ".m3u8", ignoreCase = true)
    return url
}
