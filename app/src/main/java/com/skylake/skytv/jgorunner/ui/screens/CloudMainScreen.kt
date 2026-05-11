package com.skylake.skytv.jgorunner.ui.screens

import android.app.Activity
import android.widget.Toast
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.skylake.skytv.jgorunner.data.CloudRepository
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.ui.components.MultiSelectFilterDialog
import com.skylake.skytv.jgorunner.ui.tvhome.CloudChannel
import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer
import com.skylake.skytv.jgorunner.utils.LogCollector
import kotlinx.coroutines.launch

@Composable
fun CloudMainScreen(
    context: Context,
    initialServer: CloudServer?,
    onNavigate: (String) -> Unit,
    onPlayChannel: (CloudChannel, List<CloudChannel>) -> Unit
) {
    val preferenceManager = SkySharedPref.getInstance(context)
    val repository = remember { CloudRepository(context) }
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    var currentServer by remember { mutableStateOf(initialServer) }
    var servers by remember { mutableStateOf<List<CloudServer>>(emptyList()) }
    var channels by remember { mutableStateOf<List<CloudChannel>>(emptyList()) }
    var isLoadingChannels by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var isSidebarVisible by remember { mutableStateOf(true) }
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Per-server filter storage
    val serverFiltersJson = preferenceManager.myPrefs.cloudServerFilters ?: "{}"
    val serverFiltersMap = remember(serverFiltersJson) {
        try {
            val type = object : TypeToken<Map<String, Set<String>>>() {}.type
            gson.fromJson<Map<String, Set<String>>>(serverFiltersJson, type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf<String, Set<String>>()
        }
    }

    var selectedCategories by remember(currentServer) {
        mutableStateOf(serverFiltersMap[currentServer?.url] ?: emptySet())
    }

    var showCategoryDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }

    val searchFocusRequester = remember { FocusRequester() }

    val filteredChannels = remember(channels, searchQuery, selectedCategories) {
        channels.filter { channel ->
            val matchesSearch = searchQuery.isEmpty() ||
                channel.name.contains(searchQuery, ignoreCase = true) ||
                channel.group?.contains(searchQuery, ignoreCase = true) == true ||
                channel.language?.contains(searchQuery, ignoreCase = true) == true

            val matchesCategory = selectedCategories.isEmpty() || selectedCategories.contains(channel.group)

            matchesSearch && matchesCategory
        }
    }

    LaunchedEffect(Unit) {
        servers = repository.fetchServers("https://cloudplay-app-json.pages.dev/cat/jiotv+.json")
        if (currentServer == null) {
            currentServer = servers.firstOrNull()
        }
    }

    LaunchedEffect(isSearchVisible) {
        if (isSearchVisible) {
            searchFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(currentServer) {
        currentServer?.let { server ->
            channels = emptyList()
            isLoadingChannels = true
            errorMessage = null
            try {
                val fetchedChannels = repository.fetchChannels(server.url)
                if (fetchedChannels.isEmpty()) {
                    errorMessage = "No channels found on this server."
                }
                channels = fetchedChannels
            } catch (e: Exception) {
                errorMessage = "Failed to fetch channels: ${e.localizedMessage}"
            } finally {
                isLoadingChannels = false
            }

            if (channels.isNotEmpty()) {
                if (preferenceManager.myPrefs.cloudAutoplayFirstChannel) {
                    onPlayChannel(channels.first(), channels)
                } else if (preferenceManager.myPrefs.cloudAutoplayLastChannel) {
                    val lastId = preferenceManager.myPrefs.lastCloudPlayedChannelId
                    val lastChannel = channels.find { it.id == lastId }
                    lastChannel?.let { onPlayChannel(it, channels) }
                }
            }
        }
    }

    BackHandler {
        if (isSearchVisible) {
            isSearchVisible = false
        } else if (!isSidebarVisible) {
            isSidebarVisible = true
        } else {
            onNavigate("CloudHome")
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        // Collapsible Sidebar (Servers + Settings)
        AnimatedVisibility(
            visible = isSidebarVisible,
            enter = expandHorizontally() + fadeIn(),
            exit = shrinkHorizontally() + fadeOut()
        ) {
            Row(modifier = Modifier.fillMaxHeight()) {
                // Column 1: Servers
                Column(
                    modifier = Modifier
                        .width(180.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF141414))
                        .padding(4.dp)
                ) {
                    Text("Servers", color = Color.Cyan, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        LazyColumn {
                            items(servers) { server ->
                                ServerListItem(
                                    server = server,
                                    isSelected = server.url == currentServer?.url,
                                    onSelected = { currentServer = server }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { isSidebarVisible = false }, modifier = Modifier.align(Alignment.End)) {
                        Icon(Icons.Default.ArrowBackIosNew, "Collapse", tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }

                // Column 2: Settings
                Column(
                    modifier = Modifier
                        .width(200.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF1C1C1C))
                        .padding(4.dp)
                ) {
                    Text("Settings", color = Color.Cyan, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        item {
                            SettingsToggleCompact("Autoplay First", preferenceManager.myPrefs.cloudAutoplayFirstChannel) {
                                preferenceManager.myPrefs.cloudAutoplayFirstChannel = it
                                preferenceManager.savePreferences()
                            }
                        }
                        item {
                            SettingsToggleCompact("Autoplay Last", preferenceManager.myPrefs.cloudAutoplayLastChannel) {
                                preferenceManager.myPrefs.cloudAutoplayLastChannel = it
                                preferenceManager.savePreferences()
                            }
                        }
                        item {
                            SettingsActionItemCompact("Search", Icons.Default.Search) { isSearchVisible = !isSearchVisible }
                        }
                        item {
                            SettingsActionItemCompact("Filter", Icons.Default.FilterList) { showCategoryDialog = true }
                        }
                        item {
                            SettingsActionItemCompact("Refresh", Icons.Default.Refresh) {
                                currentServer?.let {
                                    scope.launch {
                                        isLoadingChannels = true
                                        channels = repository.fetchChannels(it.url, forceRefresh = true)
                                        isLoadingChannels = false
                                    }
                                }
                            }
                        }
                        item {
                            SettingsActionItemCompact("Clear Cache", Icons.Default.DeleteSweep) {
                                repository.clearCache()
                                Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
                            }
                        }
                        item {
                            SettingsActionItemCompact("Logs", Icons.Default.BugReport) { showLogDialog = true }
                        }
                        item {
                            SettingsActionItemCompact("Reset UI", Icons.Default.RestartAlt) {
                                preferenceManager.myPrefs.cloudUiScale = 1.0f
                                preferenceManager.myPrefs.cloudAnimationEnabled = true
                                preferenceManager.myPrefs.cloudFocusAnimationEnabled = true
                                preferenceManager.myPrefs.cloudServerFilters = "{}"
                                preferenceManager.savePreferences()
                                onNavigate("CloudHome")
                            }
                        }
                        item {
                            SettingsActionItemCompact("Exit", Icons.Default.ExitToApp) { (context as? Activity)?.finishAffinity() }
                        }
                    }
                }
            }
        }

        // Conditional Search Column
        AnimatedVisibility(
            visible = isSearchVisible,
            enter = expandHorizontally() + fadeIn(),
            exit = shrinkHorizontally() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF222222))
                    .padding(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Cyan),
                    singleLine = true
                )
                Text("Found ${filteredChannels.size}", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            }
        }

        // Main Channel Grid
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                if (!isSidebarVisible) {
                    IconButton(onClick = { isSidebarVisible = true }) {
                        Icon(Icons.Default.Menu, "Expand", tint = Color.Cyan)
                    }
                }
                Text(
                    text = currentServer?.name ?: "Channels",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                if (selectedCategories.isNotEmpty()) {
                    Text(
                        text = "(${selectedCategories.size} filters)",
                        color = Color.Cyan,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { showCategoryDialog = true }
                    )
                }
            }

            if (isLoadingChannels) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.Cyan)
                }
            } else if (errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = errorMessage!!, color = Color.White)
                        Button(onClick = { onNavigate("CloudHome") }) { Text("Retry") }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredChannels) { channel ->
                        ChannelGridItemCompact(
                            channel = channel,
                            onSelected = { onPlayChannel(channel, channels) }
                        )
                    }
                }
            }
        }
    }

    if (showCategoryDialog) {
        val categories = remember(channels) {
            channels.mapNotNull { it.group }.distinct().sorted()
        }
        MultiSelectFilterDialog(
            title = "Categories",
            options = categories,
            selectedOptions = selectedCategories,
            onDismiss = { showCategoryDialog = false },
            onConfirm = {
                selectedCategories = it
                val newMap = serverFiltersMap.toMutableMap()
                if (currentServer != null) {
                    newMap[currentServer!!.url] = it
                }
                preferenceManager.myPrefs.cloudServerFilters = gson.toJson(newMap)
                preferenceManager.savePreferences()
                showCategoryDialog = false
            }
        )
    }

    if (showLogDialog) {
        LogViewerDialog(
            onDismiss = { showLogDialog = false },
            onCopy = { LogCollector.copyToClipboard(context) }
        )
    }
}

@Composable
fun LogViewerDialog(onDismiss: () -> Unit, onCopy: () -> Unit) {
    var logs by remember { mutableStateOf(LogCollector.getLogs()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Debug Logs")
                IconButton(onClick = { LogCollector.clear(); logs = "" }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear Logs", tint = Color.Red)
                }
            }
        },
        text = {
            Column {
                Text(
                    text = if (logs.isEmpty()) "No logs found." else logs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    fontSize = 12.sp,
                    color = Color.LightGray
                )
            }
        },
        confirmButton = {
            Button(onClick = onCopy) { Text("Copy to Clipboard") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        containerColor = Color(0xFF1E1E1E),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}

@Composable
fun ServerListItem(
    server: CloudServer,
    isSelected: Boolean,
    onSelected: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onSelected() },
        color = if (isSelected) Color.Cyan.copy(alpha = 0.2f) else if (isFocused) Color.White.copy(alpha = 0.1f) else Color.Transparent,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp)
        ) {
            AsyncImage(
                model = server.logo,
                contentDescription = null,
                modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = server.name,
                color = if (isSelected || isFocused) Color.Cyan else Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SettingsToggleCompact(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused }.focusable()
            .clickable { onCheckedChange(!checked) }.padding(horizontal = 8.dp, vertical = 4.dp)
            .background(if (isFocused) Color.White.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(4.dp))
            .padding(4.dp)
    ) {
        Text(label, color = if (isFocused) Color.Cyan else Color.White, modifier = Modifier.weight(1f), fontSize = 12.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.Cyan), modifier = Modifier.scale(0.7f))
    }
}

@Composable
fun SettingsActionItemCompact(label: String, icon: ImageVector, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused }.focusable()
            .clickable { onClick() }.padding(horizontal = 8.dp, vertical = 4.dp)
            .background(if (isFocused) Color.White.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        Icon(icon, null, tint = if (isFocused) Color.Cyan else Color.White, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, color = if (isFocused) Color.Cyan else Color.White, fontSize = 12.sp)
    }
}

@Composable
fun ChannelGridItemCompact(channel: CloudChannel, onSelected: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1.0f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(110.dp).scale(scale).onFocusChanged { isFocused = it.isFocused }.focusable().clickable { onSelected() }
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_UP && (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
                    onSelected(); true
                } else false
            }
    ) {
        Box(
            modifier = Modifier.aspectRatio(16f/9f).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray)
                .border(width = 2.dp, color = if (isFocused) Color.Cyan else Color.Transparent, shape = RoundedCornerShape(8.dp))
        ) {
            AsyncImage(model = channel.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            if (channel.name.contains("HD", true)) {
                Surface(color = Color.Red, shape = RoundedCornerShape(2.dp), modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                    Text("HD", color = Color.White, fontSize = 6.sp, modifier = Modifier.padding(horizontal = 2.dp))
                }
            }
        }
        Text(text = channel.name, color = if (isFocused) Color.Cyan else Color.White, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
