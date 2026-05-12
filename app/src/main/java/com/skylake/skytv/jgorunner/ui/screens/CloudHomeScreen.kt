package com.skylake.skytv.jgorunner.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ShoppingCart
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
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.skylake.skytv.jgorunner.data.CloudRepository
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

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
    val autoplayEnabledState = remember { mutableStateOf(preferenceManager.myPrefs.cloudAutoplayEnabled) }
    val focusRequesters = remember { mutableStateMapOf<Int, FocusRequester>() }

    var showCouponDialog by remember { mutableStateOf(false) }
    var subExpiry by remember { mutableLongStateOf(preferenceManager.myPrefs.cloudSubExpiry) }
    val isSubscribed = remember(subExpiry) { subExpiry > System.currentTimeMillis() }

    val freeJioServer = remember {
        CloudServer(
            name = "Free Jio",
            url = "http://localhost:${preferenceManager.myPrefs.jtvGoServerPort}/playlist.m3u",
            logo = "https://iili.io/f1zkPwP.md.png"
        )
    }

    LaunchedEffect(Unit) {
        val fetched = repository.fetchServers("https://cloudplay-app-json.pages.dev/cat/jiotv+.json")
        servers = fetched + freeJioServer
        isLoading = false
    }

    LaunchedEffect(servers, isAutoplayCancelled, isSubscribed) {
        if (isSubscribed && autoplayEnabledState.value && servers.isNotEmpty() && !isAutoplayCancelled) {
            while (countdown > 0 && !isAutoplayCancelled && autoplayEnabledState.value) {
                delay(1000)
                countdown--
            }
            if (!isAutoplayCancelled && countdown == 0 && autoplayEnabledState.value) {
                onServerSelected(servers.first())
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
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
                text = "Cloud Play",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.ExtraBold,
                    shadow = Shadow(color = Color.Cyan, blurRadius = 12f)
                ),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = if (isSubscribed) "Premium Service Active" else "Free Mode Active",
                color = if (isSubscribed) Color.Green else Color.Cyan,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 48.dp)
            )

            if (isLoading) {
                CircularProgressIndicator(color = Color.Cyan)
            } else {
                val visibleServers = if (isSubscribed) servers else listOf(freeJioServer)

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(visibleServers) { index, server ->
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

            if (!isSubscribed) {
                Spacer(modifier = Modifier.height(32.dp))
                Row {
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/@atanu_roy"))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0088CC))
                    ) {
                        Icon(Icons.Default.ShoppingCart, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Unlock Premium")
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = { showCouponDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enter Validity Key")
                    }
                }
            }

            if (isSubscribed && autoplayEnabledState.value && !isAutoplayCancelled && countdown > 0 && servers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(48.dp))
                Text(
                    text = "Autostarting in $countdown seconds...",
                    color = Color.Cyan.copy(alpha = 0.8f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (isSubscribed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Autoplay", color = Color.White, modifier = Modifier.padding(end = 8.dp), fontSize = 14.sp)
                    Switch(
                        checked = autoplayEnabledState.value,
                        onCheckedChange = {
                            autoplayEnabledState.value = it
                            preferenceManager.myPrefs.cloudAutoplayEnabled = it
                            preferenceManager.savePreferences()
                            if (!it) isAutoplayCancelled = true
                        },
                        modifier = Modifier.scale(0.8f)
                    )
                    Spacer(modifier = Modifier.width(24.dp))
                    TextButton(onClick = { showCouponDialog = true }) {
                        Text("Update Validity Key", color = Color.Cyan, fontSize = 14.sp)
                    }
                }
            }
        }

        // Professional Footer with Expiry
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val today = sdf.format(Date())
            Text(text = "Today: $today", color = Color.Gray, fontSize = 12.sp)

            if (isSubscribed) {
                val expiry = sdf.format(Date(subExpiry))
                Text(text = "Expires: $expiry", color = Color.Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(text = "No active subscription", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }

    if (showCouponDialog) {
        CouponDialog(
            onDismiss = { showCouponDialog = false },
            onKeyEntered = { key: String ->
                try {
                    val decoded = String(android.util.Base64.decode(key, android.util.Base64.DEFAULT)).trim()
                    if (decoded.length == 6) {
                        val sdf = SimpleDateFormat("ddMMyy", Locale.getDefault())
                        sdf.isLenient = false
                        val expiryDate = sdf.parse(decoded)
                        if (expiryDate != null) {
                            val cal = Calendar.getInstance()
                            cal.time = expiryDate
                            cal.set(Calendar.HOUR_OF_DAY, 23)
                            cal.set(Calendar.MINUTE, 59)
                            cal.set(Calendar.SECOND, 59)

                            val newExpiry = cal.timeInMillis
                            if (newExpiry > System.currentTimeMillis()) {
                                subExpiry = newExpiry
                                preferenceManager.myPrefs.cloudSubExpiry = newExpiry
                                preferenceManager.savePreferences()
                                showCouponDialog = false
                                val displayDate = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(expiryDate)
                                Toast.makeText(context, "Subscription valid until $displayDate", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Key represents an expired date", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Invalid date in key", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Invalid key format", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Invalid or malformed key", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
fun CouponDialog(onDismiss: () -> Unit, onKeyEntered: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1E1E1E),
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Add Validity Key", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Enter Key") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onKeyEntered(text) }) { Text("Save Key") }
                }
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

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(180.dp)
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
                .size(140.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.DarkGray)
                .then(
                    if (isFocused) {
                        Modifier.border(
                            width = 3.dp,
                            color = Color.Cyan,
                            shape = RoundedCornerShape(16.dp)
                        )
                    } else Modifier
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
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
