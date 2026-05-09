package com.skylake.skytv.jgorunner.ui.screens

import android.content.Context
import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.skylake.skytv.jgorunner.data.CloudRepository
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CloudHomeScreen(
    context: Context,
    onNavigate: (String) -> Unit,
    onServerSelected: (CloudServer) -> Unit
) {
    val preferenceManager = SkySharedPref.getInstance(context)
    val repository = remember { CloudRepository(context) }
    var servers by remember { mutableStateOf<List<CloudServer>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var countdown by remember { mutableIntStateOf(5) }
    var isAutoplayCancelled by remember { mutableStateOf(false) }
    val focusRequesters = remember { mutableStateMapOf<Int, FocusRequester>() }

    LaunchedEffect(Unit) {
        servers = repository.fetchServers("https://cloudplay-app-json.pages.dev/cat/jiotv+.json")
        isLoading = false

        if (preferenceManager.myPrefs.cloudAutoplayEnabled && servers.isNotEmpty()) {
            while (countdown > 0 && !isAutoplayCancelled) {
                delay(1000)
                countdown--
            }
            if (!isAutoplayCancelled && countdown == 0) {
                onServerSelected(servers.first())
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
            .onPreviewKeyEvent {
                if (countdown > 0 && !isAutoplayCancelled) {
                    isAutoplayCancelled = true
                }
                false
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Select a Server",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    shadow = Shadow(color = Color.Cyan, blurRadius = 8f)
                ),
                modifier = Modifier.padding(bottom = 48.dp)
            )

            if (isLoading) {
                CircularProgressIndicator(color = Color.Cyan)
            } else if (servers.isEmpty()) {
                Text(text = "No servers found", color = Color.Gray)
                Button(onClick = { isLoading = true; /* Refresh logic */ }) {
                    Text("Retry")
                }
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(servers) { index, server ->
                        val focusRequester = focusRequesters.getOrPut(index) { FocusRequester() }
                        ServerCard(
                            server = server,
                            focusRequester = focusRequester,
                            uiScale = preferenceManager.myPrefs.cloudUiScale,
                            animationEnabled = preferenceManager.myPrefs.cloudAnimationEnabled,
                            focusAnimationEnabled = preferenceManager.myPrefs.cloudFocusAnimationEnabled,
                            onSelected = {
                                isAutoplayCancelled = true
                                onServerSelected(server)
                            }
                        )

                        if (index == 0) {
                            LaunchedEffect(Unit) {
                                focusRequester.requestFocus()
                            }
                        }
                    }
                }
            }

            if (preferenceManager.myPrefs.cloudAutoplayEnabled && !isAutoplayCancelled && countdown > 0 && servers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(48.dp))
                Text(
                    text = "Autostarting in $countdown seconds...",
                    color = Color.Cyan.copy(alpha = 0.8f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Press any key to cancel",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Autoplay", color = Color.White, modifier = Modifier.padding(end = 8.dp))
                Switch(
                    checked = preferenceManager.myPrefs.cloudAutoplayEnabled,
                    onCheckedChange = {
                        preferenceManager.myPrefs.cloudAutoplayEnabled = it
                        preferenceManager.savePreferences()
                        if (!it) isAutoplayCancelled = true
                    }
                )
            }
        }
    }
}

@Composable
fun ServerCard(
    server: CloudServer,
    focusRequester: FocusRequester,
    uiScale: Float = 1.0f,
    animationEnabled: Boolean = true,
    focusAnimationEnabled: Boolean = true,
    onSelected: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused && focusAnimationEnabled) 1.15f * uiScale else 1.0f * uiScale)
    val glowAlpha by animateFloatAsState(if (isFocused) 1f else 0f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(200.dp)
            .scale(scale)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onSelected() }
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                    (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                     it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
                    onSelected()
                    true
                } else false
            }
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.DarkGray)
                .border(
                    width = 3.dp,
                    color = if (isFocused) Color.Cyan else Color.Transparent,
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            AsyncImage(
                model = server.logo,
                contentDescription = server.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = server.name,
            color = if (isFocused) Color.Cyan else Color.White,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
