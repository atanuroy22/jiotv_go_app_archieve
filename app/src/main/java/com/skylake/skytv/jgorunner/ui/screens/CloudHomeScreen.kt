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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.skylake.skytv.jgorunner.data.CloudRepository
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.data.selectSdServerWithFallback
import com.skylake.skytv.jgorunner.ui.components.MultiSelectFilterDialog
import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

private const val JIO_SERVER_LIST_URL = "https://cloudplay-app-json.pages.dev/cat/jiotv+.json"
private const val ZEE5_SERVER_LIST_URL = "https://cloudplay-app-json.pages.dev/cat/zee5.json"
private const val SONY_SERVER_LIST_URL = "https://cloudplay-app-json.pages.dev/cat/sony.json"
private const val SPORTS_SERVER_LIST_URL = "https://cloudplay-app-json.pages.dev/cat/sports.json"

@Composable
fun CloudHomeScreen(
    context: Context,
    onServerSelected: (CloudServer) -> Unit,
    onNavigate: (String) -> Unit
) {
    val preferenceManager = SkySharedPref.getInstance(context)
    val repository = remember { CloudRepository(context) }
    val gson = remember { Gson() }

    var allServers by remember { mutableStateOf<List<CloudServer>>(emptyList()) }
    var servers by remember { mutableStateOf<List<CloudServer>>(emptyList()) }
    var countdown by remember { mutableIntStateOf(5) }
    var isAutoplayActive by remember { mutableStateOf(false) }
    var showCouponDialog by remember { mutableStateOf(false) }
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showHiddenServersDialog by remember { mutableStateOf(false) }
    var showAutoplayServerDialog by remember { mutableStateOf(false) }

    var refreshTrigger by remember { mutableIntStateOf(0) }

    val subExpiry = remember(refreshTrigger) { preferenceManager.myPrefs.cloudSubExpiry }
    val isSubscribed = remember(subExpiry) { subExpiry > System.currentTimeMillis() }

    val hiddenServersJson = preferenceManager.myPrefs.cloudHiddenServerUrls ?: "[]"
    val hiddenServerUrls = remember(hiddenServersJson) {
        try {
            gson.fromJson<List<String>>(hiddenServersJson, object : TypeToken<List<String>>() {}.type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    val autoplayServerUrl = preferenceManager.myPrefs.cloudAutoplayServerUrl
    var autoplayDelaySeconds by remember { mutableIntStateOf(preferenceManager.myPrefs.cloudAutoplayDelaySeconds) }
    var showAutoplayDelayMenu by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(refreshTrigger) {
        val jioServers = repository.fetchServers(JIO_SERVER_LIST_URL)
        val zee5Servers = selectSdServerWithFallback(repository.fetchServers(ZEE5_SERVER_LIST_URL))
        val sonyServers = repository.fetchServers(SONY_SERVER_LIST_URL)
        val sportsServers = repository.fetchServers(SPORTS_SERVER_LIST_URL)
        val fetched = (jioServers + zee5Servers + sonyServers + sportsServers).distinctBy { it.url }
        val freeJio = CloudServer(
            name = "Free Jio",
            url = "http://localhost:${preferenceManager.myPrefs.jtvGoServerPort}/playlist.m3u",
            logo = "https://iili.io/f1zkPwP.md.png"
        )

        val baseList = if (isSubscribed) {
            fetched + freeJio
        } else {
            listOf(freeJio)
        }

        val visibleServers = baseList.filter { it.url !in hiddenServerUrls }
        allServers = baseList
        servers = visibleServers
    }

    LaunchedEffect(autoplayDelaySeconds, servers, autoplayServerUrl) {
        if (autoplayDelaySeconds > 0 && servers.isNotEmpty()) {
            isAutoplayActive = true
            countdown = autoplayDelaySeconds
            while (countdown > 0 && autoplayDelaySeconds > 0 && isAutoplayActive) {
                delay(1000)
                countdown--
            }
            if (countdown == 0 && autoplayDelaySeconds > 0 && isAutoplayActive) {
                val target = servers.firstOrNull { it.url == autoplayServerUrl } ?: servers.first()
                onServerSelected(target)
            }
        } else {
            isAutoplayActive = false
        }
    }

    LaunchedEffect(hiddenServerUrls, autoplayServerUrl) {
        if (autoplayServerUrl != null && autoplayServerUrl in hiddenServerUrls) {
            preferenceManager.myPrefs.cloudAutoplayServerUrl = null
            preferenceManager.savePreferences()
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
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && isAutoplayActive) {
                    isAutoplayActive = false
                }
                false
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Cloud Play",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Cyan,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showSettingsPanel = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.Cyan)
                }
            }
            Text(
                text = "Select a server to start streaming",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            if (servers.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.Cyan)
                        if (allServers.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "All servers are hidden. Open settings to unhide.",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
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
                    text = "Autoplay in $countdown... Any key or touch to cancel",
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

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = expiryText,
                        color = if (isSubscribed) Color.Green else Color.Yellow,
                        fontSize = 11.sp
                    )
                    val autoplayName = allServers.firstOrNull { it.url == autoplayServerUrl }?.name ?: "Auto"
                    val hiddenCount = hiddenServerUrls.size
                    Text(
                        text = "Autoplay: $autoplayName | Hidden: $hiddenCount",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }

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

    if (showSettingsPanel) {
        val serverOptions = remember(allServers) {
            allServers.map { server ->
                server.name to server.url
            }
        }
        val autoplayDelayOptions = listOf(0, 3, 5, 10, 15, 20, 30)
        val autoplayDelayLabels = mapOf(
            0 to "Never",
            3 to "3 sec",
            5 to "5 sec",
            10 to "10 sec",
            15 to "15 sec",
            20 to "20 sec",
            30 to "30 sec"
        )

        AlertDialog(
            onDismissRequest = { showSettingsPanel = false },
            title = { Text("Cloud Settings", fontSize = 16.sp, color = Color.Cyan) },
            text = {
                Column {
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAutoplayDelayMenu = true }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Schedule, null, tint = Color.Cyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Autoplay: ${autoplayDelayLabels[autoplayDelaySeconds]}", color = Color.White)
                        }
                        DropdownMenu(
                            expanded = showAutoplayDelayMenu,
                            onDismissRequest = { showAutoplayDelayMenu = false }
                        ) {
                            autoplayDelayOptions.forEach { seconds ->
                                DropdownMenuItem(
                                    text = { Text(autoplayDelayLabels[seconds] ?: "Unknown") },
                                    onClick = {
                                        autoplayDelaySeconds = seconds
                                        preferenceManager.myPrefs.cloudAutoplayDelaySeconds = seconds
                                        preferenceManager.savePreferences()
                                        showAutoplayDelayMenu = false
                                    }
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showHiddenServersDialog = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VisibilityOff, null, tint = Color.Cyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Hidden Servers (${hiddenServerUrls.size})", color = Color.White)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAutoplayServerDialog = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlayCircle, null, tint = Color.Cyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        val autoplayName = servers.firstOrNull { it.url == autoplayServerUrl }?.name ?: "Auto"
                        Text("Autoplay Server: $autoplayName", color = Color.White)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsPanel = false }) {
                    Text("Close", color = Color.Cyan)
                }
            },
            containerColor = Color(0xFF1A1A1A),
            textContentColor = Color.White,
            titleContentColor = Color.White
        )

        if (showHiddenServersDialog) {
            val optionLabels = serverOptions.map { it.first }
            val urlByLabel = serverOptions.toMap()
            val selectedLabels = hiddenServerUrls.mapNotNull { url ->
                serverOptions.firstOrNull { it.second == url }?.first
            }.toSet()

            MultiSelectFilterDialog(
                title = "Hidden Servers",
                options = optionLabels,
                selectedOptions = selectedLabels,
                onDismiss = { showHiddenServersDialog = false },
                onConfirm = { selected ->
                    val urls = selected.mapNotNull { label -> urlByLabel[label] }
                    preferenceManager.myPrefs.cloudHiddenServerUrls = gson.toJson(urls)
                    preferenceManager.savePreferences()
                    showHiddenServersDialog = false
                    refreshTrigger++
                }
            )
        }

        if (showAutoplayServerDialog) {
            val optionLabels = servers.map { it.name }
            val urlByLabel = serverOptions.toMap()
            val selectedLabel = servers.firstOrNull { it.url == autoplayServerUrl }?.name

            MultiSelectFilterDialog(
                title = "Autoplay Server",
                options = optionLabels,
                selectedOptions = if (selectedLabel == null) emptySet() else setOf(selectedLabel),
                singleSelect = true,
                onDismiss = { showAutoplayServerDialog = false },
                onConfirm = { selected ->
                    val url = selected.firstOrNull()?.let { urlByLabel[it] }
                    preferenceManager.myPrefs.cloudAutoplayServerUrl = url
                    preferenceManager.savePreferences()
                    showAutoplayServerDialog = false
                    refreshTrigger++
                }
            )
        }
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
