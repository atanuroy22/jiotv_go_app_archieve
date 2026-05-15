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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

private const val JIO_SERVER_LIST_URL = "https://cloudplay-app-json.pages.dev/cat/jiotv+.json"
private const val HOTSTAR_SERVER_LIST_URL = "https://cloudplay-app-json.pages.dev/cat/hotstar.json"

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

    var refreshTrigger by remember { mutableIntStateOf(0) }

    val subExpiry = remember(refreshTrigger) { preferenceManager.myPrefs.cloudSubExpiry }
    val isSubscribed = remember(subExpiry) { subExpiry > System.currentTimeMillis() }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(refreshTrigger) {
        val jioServers = repository.fetchServers(JIO_SERVER_LIST_URL)
        val hotstarServers = repository.fetchServers(HOTSTAR_SERVER_LIST_URL)
        val fetched = (jioServers + hotstarServers).distinctBy { it.url }
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
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
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
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            if (servers.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.Cyan)
                }
            } else {
                // Changed to Vertical Grid to fit mobile and TV without horizontal hiding
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester)
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
                }

                LaunchedEffect(Unit) {
                    delay(500)
                    try { focusRequester.requestFocus() } catch(_: Exception) {}
                }
            }

            if (isAutoplayActive && servers.isNotEmpty()) {
                Text(
                    text = "Autoplay in $countdown... Any key to cancel",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            }

            // Footer info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                val expiryText = if (isSubscribed) {
                    "Valid until: ${sdf.format(Date(subExpiry))}"
                } else {
                    "🎉 Get Jio+ & Sports at Lowest Price!"
                }

                Text(
                    text = expiryText,
                    color = if (isSubscribed) Color.Green else Color.Yellow,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )

                if (isSubscribed) {
                    TextButton(onClick = {
                        preferenceManager.myPrefs.cloudSubExpiry = 0L
                        preferenceManager.savePreferences()
                        Toast.makeText(context, "Subscription removed", Toast.LENGTH_SHORT).show()
                        refreshTrigger++
                    }, contentPadding = PaddingValues(4.dp)) {
                        Text("Remove Key", color = Color.Red, fontSize = 11.sp)
                    }
                } else {
                    TextButton(onClick = { showCouponDialog = true }, contentPadding = PaddingValues(4.dp)) {
                        Text("Add Key", color = Color.Cyan, fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                TextButton(onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/atanu_roy"))
                    context.startActivity(intent)
                }, contentPadding = PaddingValues(4.dp)) {
                    Icon(Icons.Default.ShoppingCart, null, tint = Color.Cyan, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Buy", color = Color.Cyan, fontSize = 11.sp)
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
                    refreshTrigger++
                } else {
                    Toast.makeText(context, "Invalid key format or expired!", Toast.LENGTH_SHORT).show()
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
            .fillMaxWidth()
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
                .size(80.dp)
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
        val cleanKey = rawKey.trim()
        val decoded = String(android.util.Base64.decode(cleanKey, android.util.Base64.DEFAULT))
        val match = Regex("""(\d{6})""").find(decoded) ?: return null
        val datePart = match.groupValues[1]
        val day = datePart.substring(0, 2).toInt()
        val month = datePart.substring(2, 4).toInt() - 1
        val year = 2000 + datePart.substring(4, 6).toInt()
        val cal = Calendar.getInstance()
        cal.set(year, month, day, 23, 59, 59)
        val timestamp = cal.timeInMillis
        if (timestamp < System.currentTimeMillis() - 86400000) null else timestamp
    } catch (e: Exception) {
        null
    }
}
