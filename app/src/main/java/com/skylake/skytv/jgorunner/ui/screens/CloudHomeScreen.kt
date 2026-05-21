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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
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

private const val CLOUD_SRC_A = "aHR0cHM6Ly9jbG91ZHBsYXktYXBwLWpzb24ucGFnZXMuZGV2L2NhdC9qaW90disuanNvbg=="
private const val CLOUD_SRC_B = "aHR0cHM6Ly9jbG91ZHBsYXktYXBwLWpzb24ucGFnZXMuZGV2L2NhdC96ZWU1Lmpzb24="
private const val CLOUD_SRC_C = "aHR0cHM6Ly9jbG91ZHBsYXktYXBwLWpzb24ucGFnZXMuZGV2L2NhdC9zb255Lmpzb24="
private const val CLOUD_SRC_D = "aHR0cHM6Ly9jbG91ZHBsYXktYXBwLWpzb24ucGFnZXMuZGV2L2NhdC9zcG9ydHMuanNvbg=="
private const val CLOUD_SRC_E = "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL2RybWxpdmUvZmFuY29kZS1saXZlLWV2ZW50cy9tYWluL2ZhbmNvZGUuanNvbg=="
private const val CLOUD_SRC_F = "aHR0cHM6Ly9hbGxpbm9uZXJlYm9ybi5vbmxpbmUvdHBsYXkvY2hhbm5lbHMuanNvbg=="
private const val CLOUD_SRC_G = "aHR0cHM6Ly9hbGxpbm9uZXJlYm9ybi5vbmxpbmUvanR2LWZldGNoL2pzdHI0d2ViLmpzb24="

private fun decodeCloudUrl(encoded: String): String =
    String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))

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
    var isAutoopenActive by remember { mutableStateOf(false) }
    var autoopenConsumed by rememberSaveable { mutableStateOf(false) }
    var showCouponDialog by remember { mutableStateOf(false) }
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showHiddenServersDialog by remember { mutableStateOf(false) }
    var showAutoopenServerDialog by remember { mutableStateOf(false) }

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

    var autoopenServerUrl by remember { mutableStateOf(preferenceManager.myPrefs.cloudAutoplayServerUrl) }
    var autoopenDelaySeconds by remember { mutableIntStateOf(preferenceManager.myPrefs.cloudAutoplayDelaySeconds) }
    var showAutoopenDelayMenu by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val serverFocusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }

    LaunchedEffect(refreshTrigger) {
        val jioServers = repository.fetchServers(decodeCloudUrl(CLOUD_SRC_A))
        val zee5Servers = selectSdServerWithFallback(repository.fetchServers(decodeCloudUrl(CLOUD_SRC_B)))
        val sonyServers = repository.fetchServers(decodeCloudUrl(CLOUD_SRC_C))
        val sportsServers = repository.fetchServers(decodeCloudUrl(CLOUD_SRC_D))
        
        val freeJio = CloudServer(
            name = "Free Jio",
            url = "http://localhost:${preferenceManager.myPrefs.jtvGoServerPort}/playlist.m3u",
            logo = "https://raw.githubusercontent.com/atanuroy22/jiotv_go_app/develop/pic/jiotv.jpg"
        )
        val tataBingServer = CloudServer(
            name = "Tata Bing",
            url = decodeCloudUrl(CLOUD_SRC_F),
            logo = "https://downloadr2.apkmirror.com/wp-content/uploads/2022/01/95/61f1ed6874463.png"
        )
        
        val fancodeServer = CloudServer(
            name = "Fancode Live",
            url = decodeCloudUrl(CLOUD_SRC_E),
            logo = "https://downloadr2.apkmirror.com/wp-content/uploads/2021/06/26/60d9761924e40.png"
        )

        val jioCrystalServer = CloudServer(
            name = "Jio Crystal",
            url = decodeCloudUrl(CLOUD_SRC_G),
            logo = "https://yt3.googleusercontent.com/ytc/AIdro_lKULdxBE4H3HJlomG_vs3XMDk6FnCQA6zgO0EVZH5Kvg=s900-c-k-c0x00ffffff-no-rj"
        )

        // Reorder: Jio first, then Free Jio, then others. Hide Zee5 and Sports by default.
        val baseList = if (isSubscribed) {
            (jioServers + listOf(freeJio, fancodeServer, tataBingServer, jioCrystalServer) + zee5Servers + sonyServers + sportsServers).distinctBy { it.url }
        } else {
            listOf(freeJio)
        }

        var currentHiddenUrls = hiddenServerUrls.toMutableList()
        
        // Set default hidden servers on first run
        if (hiddenServerUrls.isEmpty() && isSubscribed && baseList.isNotEmpty()) {
            currentHiddenUrls = baseList
                .filter { server ->
                    // Keep only JioTV+, Free Jio, and Sony (IN) visible. Hide everything else and slow/unresponsive servers.
                    val isJio = server.name.contains("jio", ignoreCase = true) && !server.name.contains("sony", ignoreCase = true)
                    val isSonyIn = server.name.contains("sony", ignoreCase = true) && server.name.contains("in", ignoreCase = true)
                    val isSlow = server.name.contains("slow", ignoreCase = true)
                    val isFancode = server.name.contains("fancode", ignoreCase = true)
                    val isZeeSd = server.name.contains("zee", ignoreCase = true) &&
                        server.name.contains("sd", ignoreCase = true)
                    val isTataBing = server.name.contains("tata bing", ignoreCase = true)
                    val isCrystal = server.name.contains("crystal", ignoreCase = true)
                    !(isJio || isSonyIn || isFancode || isZeeSd || isTataBing || isCrystal) || (isSlow && !isJio)
                }
                .map { it.url }
                .toMutableList()
            
            if (currentHiddenUrls.isNotEmpty()) {
                preferenceManager.myPrefs.cloudHiddenServerUrls = gson.toJson(currentHiddenUrls)
                preferenceManager.savePreferences()
            }
        }

        val visibleServers = baseList.filter { it.url !in currentHiddenUrls }
        allServers = baseList
        servers = visibleServers

        if (visibleServers.isNotEmpty()) {
            val resolvedAutoopenUrl = when {
                !isSubscribed -> freeJio.url
                autoopenServerUrl != null && visibleServers.any { it.url == autoopenServerUrl } -> autoopenServerUrl
                else -> visibleServers.first().url
            }
            if (autoopenServerUrl != resolvedAutoopenUrl) {
                autoopenServerUrl = resolvedAutoopenUrl
                preferenceManager.myPrefs.cloudAutoplayServerUrl = resolvedAutoopenUrl
                preferenceManager.savePreferences()
            }
        }
    }

    LaunchedEffect(autoopenDelaySeconds, servers, autoopenServerUrl, autoopenConsumed) {
        if (!autoopenConsumed && autoopenDelaySeconds > 0 && servers.isNotEmpty()) {
            isAutoopenActive = true
            countdown = autoopenDelaySeconds
            while (countdown > 0 && autoopenDelaySeconds > 0 && isAutoopenActive && !autoopenConsumed) {
                delay(1000)
                if (!showSettingsPanel && !showHiddenServersDialog && !showAutoopenServerDialog && !showCouponDialog) {
                    countdown--
                }
            }
            if (countdown == 0 && autoopenDelaySeconds > 0 && isAutoopenActive && !autoopenConsumed) {
                autoopenConsumed = true
                val subscriptionActiveNow = preferenceManager.myPrefs.cloudSubExpiry > System.currentTimeMillis()
                val target = if (subscriptionActiveNow) {
                    servers.firstOrNull { it.url == autoopenServerUrl } ?: servers.firstOrNull()
                } else {
                    servers.firstOrNull {
                        it.name.contains("free jio", ignoreCase = true) ||
                            it.url.contains("localhost", ignoreCase = true) ||
                            it.url.contains("127.0.0.1")
                    } ?: servers.firstOrNull()
                }
                if (target != null) onServerSelected(target)
            }
        } else {
            isAutoopenActive = false
        }
    }

    LaunchedEffect(hiddenServerUrls, autoopenServerUrl) {
        if (autoopenServerUrl != null && autoopenServerUrl in hiddenServerUrls) {
            autoopenServerUrl = servers.firstOrNull()?.url ?: autoopenServerUrl
            preferenceManager.myPrefs.cloudAutoplayServerUrl = autoopenServerUrl
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
            .clickable(enabled = isAutoopenActive) {
                isAutoopenActive = false
            }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && isAutoopenActive) {
                    isAutoopenActive = false
                }
                false
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Cloud Play",
                    fontSize = 24.sp,
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
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
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
                    columns = GridCells.Adaptive(minSize = 130.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester)
                ) {
                    items(servers) { server ->
                        val itemFocusRequester = serverFocusRequesters.getOrPut(server.url) { FocusRequester() }
                        ServerCard(
                            server = server,
                            modifier = Modifier.focusRequester(itemFocusRequester),
                            onSelected = {
                                isAutoopenActive = false
                                autoopenConsumed = true
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

            if (isAutoopenActive && servers.isNotEmpty()) {
                Text(
                    text = "Autoopen in $countdown... Any key or touch to cancel",
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
                    "🎉 Jio+ & Sports & Sony at Lowest Price!"
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = expiryText,
                        color = if (isSubscribed) Color.Green else Color.Yellow,
                        fontSize = 11.sp
                    )
                    val autoopenName = allServers.firstOrNull { it.url == autoopenServerUrl }?.name ?: servers.firstOrNull()?.name ?: ""
                    val hiddenCount = hiddenServerUrls.size
                    Text(
                        text = "AutoOpen: $autoopenName | Hidden: $hiddenCount",
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
        val autoopenDelayOptions = listOf(0, 3, 5, 10, 15, 20, 30)
        val autoopenDelayLabels = mapOf(
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
                                .clickable { showAutoopenDelayMenu = true }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Schedule, null, tint = Color.Cyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Autoopen: ${autoopenDelayLabels[autoopenDelaySeconds]}", color = Color.White)
                        }
                        DropdownMenu(
                            expanded = showAutoopenDelayMenu,
                            onDismissRequest = { showAutoopenDelayMenu = false }
                        ) {
                            autoopenDelayOptions.forEach { seconds ->
                                DropdownMenuItem(
                                    text = { Text(autoopenDelayLabels[seconds] ?: "Unknown") },
                                    onClick = {
                                        autoopenConsumed = false
                                        autoopenDelaySeconds = seconds
                                        preferenceManager.myPrefs.cloudAutoplayDelaySeconds = seconds
                                        preferenceManager.savePreferences()
                                        showAutoopenDelayMenu = false
                                    }
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAutoopenServerDialog = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlayCircle, null, tint = Color.Cyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        val autoopenName = servers.firstOrNull { it.url == autoopenServerUrl }?.name ?: servers.firstOrNull()?.name ?: "Auto"
                        Text("Autoopen Server: $autoopenName", color = Color.White)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusable()
                            .clickable { showHiddenServersDialog = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VisibilityOff, null, tint = Color.Cyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Hidden Servers (${hiddenServerUrls.size})", color = Color.White)
                    }
                    androidx.compose.material3.HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                preferenceManager.myPrefs.cloudHiddenServerUrls = "[]"
                                preferenceManager.myPrefs.cloudAutoplayServerUrl = null
                                preferenceManager.myPrefs.cloudAutoplayDelaySeconds = 0
                                preferenceManager.myPrefs.cloudAutoplayFirstChannel = false
                                preferenceManager.myPrefs.cloudAutoplayLastChannel = false
                                preferenceManager.myPrefs.lastCloudPlayedChannelId = null
                                preferenceManager.myPrefs.lastCloudServerUrl = null
                                preferenceManager.myPrefs.lastCloudServerName = null
                                preferenceManager.myPrefs.cloudUiScale = 1.0f
                                preferenceManager.myPrefs.cloudAnimationEnabled = true
                                preferenceManager.myPrefs.cloudFocusAnimationEnabled = true
                                preferenceManager.myPrefs.cloudQualityMaxHeight = 0
                                preferenceManager.myPrefs.cloudServerFilters = "{}"
                                preferenceManager.myPrefs.cloudLanguageFilter = ""
                                preferenceManager.myPrefs.cloudCategoryFilter = null
                                preferenceManager.myPrefs.filterQX = null
                                preferenceManager.savePreferences()
                                autoopenServerUrl = null
                                autoopenDelaySeconds = 0
                                autoopenConsumed = false
                                showSettingsPanel = false
                                refreshTrigger++
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.RestartAlt, null, tint = Color.Red, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Reset All Settings", color = Color.Red)
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

    }

    if (showHiddenServersDialog) {
    val serverOptions = remember(allServers) {
        allServers.map { server -> server.name to server.url }
    }
    val optionLabels = serverOptions.map { it.first }
    val urlByLabel = serverOptions.toMap()
    val selectedLabels = remember(hiddenServerUrls, allServers) {
        hiddenServerUrls.mapNotNull { url ->
            serverOptions.firstOrNull { it.second == url }?.first
        }.toSet()
    }

    MultiSelectFilterDialog(
        title = "Hidden Servers",
        options = optionLabels,
        selectedOptions = selectedLabels,
        onDismiss = { showHiddenServersDialog = false },
        onConfirm = { newSelectedLabels ->
            val newHidden = allServers
                .filter { server -> server.name in newSelectedLabels }
                .map { it.url }
            preferenceManager.myPrefs.cloudHiddenServerUrls = gson.toJson(newHidden)
            preferenceManager.savePreferences()
            showHiddenServersDialog = false
            refreshTrigger++
        }
    )
}

    if (showAutoopenServerDialog) {
        val optionPairs = servers.mapIndexed { index, server -> "${index + 1}. ${server.name}" to server.url }
        val optionLabels = optionPairs.map { it.first }
        val urlByLabel = optionPairs.toMap()
        val selectedLabel = optionPairs.firstOrNull { it.second == autoopenServerUrl }?.first

        MultiSelectFilterDialog(
            title = "Autoopen Server",
            options = optionLabels,
            selectedOptions = if (selectedLabel == null) emptySet() else setOf(selectedLabel),
            singleSelect = true,
            onDismiss = { showAutoopenServerDialog = false },
            onConfirm = { selected ->
                autoopenConsumed = false
                val url = selected.firstOrNull()?.let { urlByLabel[it] }
                if (url != null) {
                    autoopenServerUrl = url
                    preferenceManager.myPrefs.cloudAutoplayServerUrl = url
                    preferenceManager.savePreferences()
                }
                showAutoopenServerDialog = false
            }
        )
    }

}

@Composable
fun ServerCard(
    server: CloudServer,
    modifier: Modifier = Modifier,
    onSelected: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.05f else 1.0f)
    val glowColor = if (isFocused) Color.Cyan else Color.Transparent

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.1f) else Color(0xFF1A1A1A))
            .border(2.dp, glowColor, RoundedCornerShape(12.dp))
            .clickable { onSelected() }
            .focusable()
            .padding(8.dp)
    ) {
        AsyncImage(
            model = server.logo,
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(6.dp))
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
