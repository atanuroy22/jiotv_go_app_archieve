package com.skylake.skytv.jgorunner.ui.screens

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.skylake.skytv.jgorunner.data.CloudRepository
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun CloudHomeScreen(
    context: Context,
    onServerSelected: (CloudServer) -> Unit,
    onNavigate: (String) -> Unit
) {
    val preferenceManager = SkySharedPref.getInstance(context)
    val repository = remember { CloudRepository(context) }

    var servers by remember { mutableStateOf<List<CloudServer>>(emptyList()) }
    var countdown by remember { mutableIntStateOf(5) }
    var isAutoplayActive by remember { mutableStateOf(preferenceManager.myPrefs.cloudAutoplayEnabled) }
    var showCouponDialog by remember { mutableStateOf(false) }

    val subExpiry = preferenceManager.myPrefs.cloudSubExpiry
    val isSubscribed = subExpiry > System.currentTimeMillis()

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        val fetched = repository.fetchServers("https://cloudplay-app-json.pages.dev/cat/jiotv+.json")
        val freeJio = CloudServer(
            name = "Free Jio",
            url = "http://localhost:${preferenceManager.myPrefs.jtvGoServerPort}/playlist.m3u",
            logo = "https://iili.io/f1zkPwP.md.png"
        )

        servers = if (isSubscribed) {
            fetched + freeJio
        } else {
            listOf(freeJio)
        }
    }

    LaunchedEffect(isAutoplayActive, servers) {
        if (isAutoplayActive && servers.isNotEmpty()) {
            while (countdown > 0) {
                delay(1000)
                countdown--
            }
            if (countdown == 0) {
                onServerSelected(servers.first())
            }
        }
    }

    BackHandler {
        (context as? Activity)?.finishAffinity()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .clickable(enabled = isAutoplayActive) {
                isAutoplayActive = false
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Cloud Play",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Cyan
            )
            Text(
                text = "Select a server to start streaming",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp, bottom = 40.dp)
            )

            if (servers.isEmpty()) {
                CircularProgressIndicator(color = Color.Cyan)
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.focusRequester(focusRequester).fillMaxWidth()
                ) {
                    items(servers) { server ->
                        ServerCard(
                            server = server,
                            onSelected = {
                                isAutoplayActive = false
                                onServerSelected(server)
                            }
                        )
                    }
                    if (servers.size > 2) {
                        item {
                            Box(modifier = Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    delay(500)
                    try { focusRequester.requestFocus() } catch(_: Exception) {}
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (isAutoplayActive && servers.isNotEmpty()) {
                Text(
                    text = "Autoplay in $countdown... Any key to cancel",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 20.dp)
                )
            }

            // Footer info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            ) {
                val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                val expiryText = if (isSubscribed) {
                    "Subscription valid until: ${sdf.format(Date(subExpiry))}"
                } else {
                    "Free Mode (Local Jio enabled)"
                }

                Text(
                    text = expiryText,
                    color = if (isSubscribed) Color.Green else Color.Yellow,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )

                if (isSubscribed) {
                    TextButton(onClick = {
                        preferenceManager.myPrefs.cloudSubExpiry = 0L
                        preferenceManager.savePreferences()
                        Toast.makeText(context, "Subscription removed", Toast.LENGTH_SHORT).show()
                        onNavigate("CloudHome")
                    }) {
                        Text("Remove Key", color = Color.Red, fontSize = 12.sp)
                    }
                } else {
                    TextButton(onClick = { showCouponDialog = true }) {
                        Text("Add Key", color = Color.Cyan, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                TextButton(onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/atanu_roy"))
                    context.startActivity(intent)
                }) {
                    Icon(Icons.Default.ShoppingCart, null, tint = Color.Cyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Buy Subscription", color = Color.Cyan, fontSize = 12.sp)
                }
            }
        }
    }

    if (showCouponDialog) {
        CouponDialog(
            onDismiss = { showCouponDialog = false },
            onApply = { key ->
                val validity = validateKey(key)
                if (validity != null) {
                    preferenceManager.myPrefs.cloudSubExpiry = validity
                    preferenceManager.savePreferences()
                    Toast.makeText(context, "Key applied!", Toast.LENGTH_LONG).show()
                    showCouponDialog = false
                    onNavigate("CloudHome")
                } else {
                    Toast.makeText(context, "Invalid key!", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
fun ServerCard(
    server: CloudServer,
    onSelected: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.05f else 1.0f)
    val glowColor = if (isFocused) Color.Cyan else Color.Transparent

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(160.dp)
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.1f) else Color(0xFF1A1A1A))
            .border(2.dp, glowColor, RoundedCornerShape(12.dp))
            .clickable { onSelected() }
            .focusable()
            .padding(16.dp)
    ) {
        AsyncImage(
            model = server.logo,
            contentDescription = null,
            modifier = Modifier
                .size(90.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = server.name,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
fun CouponDialog(onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Access Key", color = Color.Cyan) },
        text = {
            Column {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Key") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Cyan),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = { onApply(key) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan)) {
                Text("Apply", color = Color.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = Color(0xFF1A1A1A),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}

fun validateKey(rawKey: String): Long? {
    return try {
        val decoded = String(android.util.Base64.decode(rawKey, android.util.Base64.DEFAULT))

        if (!decoded.startsWith("CP")) return null
        val datePart = decoded.substring(2)
        if (datePart.length != 6) return null

        val day = datePart.substring(0, 2).toInt()
        val month = datePart.substring(2, 4).toInt() - 1
        val year = 2000 + datePart.substring(4, 6).toInt()

        val cal = Calendar.getInstance()
        cal.set(year, month, day, 23, 59, 59)
        val timestamp = cal.timeInMillis

        if (timestamp < System.currentTimeMillis()) null else timestamp
    } catch (e: Exception) {
        null
    }
}
