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

    var currentServer by remember { mutableStateOf(initialServer) }
    var servers by remember { mutableStateOf<List<CloudServer>>(emptyList()) }
    var channels by remember { mutableStateOf<List<CloudChannel>>(emptyList()) }
    var isLoadingChannels by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    var selectedCategories by remember {
        mutableStateOf(
            preferenceManager.myPrefs.cloudCategoryFilter?.split(",")?.toSet()?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        )
    }
    var selectedLanguages by remember { mutableStateOf(emptySet<String>()) }

    var showCategoryDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }

    val serverListFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }
    val channelsFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }

    val filteredChannels = remember(channels, searchQuery, selectedCategories, selectedLanguages) {
        channels.filter { channel ->
            val matchesSearch = searchQuery.isEmpty() ||
                channel.name.contains(searchQuery, ignoreCase = true) ||
                channel.group?.contains(searchQuery, ignoreCase = true) == true ||
                channel.language?.contains(searchQuery, ignoreCase = true) == true

            val matchesCategory = selectedCategories.isEmpty() || selectedCategories.contains(channel.group)
            val matchesLanguage = selectedLanguages.isEmpty() || selectedLanguages.contains(channel.language)

            matchesSearch && matchesCategory && matchesLanguage
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
            isLoadingChannels = true
            errorMessage = null
            try {
                val fetchedChannels = repository.fetchChannels(server.url)
                if (fetchedChannels.isEmpty()) {
                    errorMessage = "No channels found or network error."
                }
                channels = fetchedChannels
            } catch (e: Exception) {
                errorMessage = "Failed to fetch channels: ${e.localizedMessage}"
                Log.e("CloudMainScreen", "Error fetching channels", e)
            } finally {
                isLoadingChannels = false
            }

            // Autoplay logic if enabled
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
        } else {
            onNavigate("CloudHome")
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        // Left Column: Servers
        Column(
            modifier = Modifier
                .width(200.dp)
                .fillMaxHeight()
                .background(Color(0xFF1A1A1A))
                .padding(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.Default.Dns, contentDescription = null, tint = Color.Cyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Servers", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(modifier = Modifier.weight(1f)) {
                if (servers.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
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
            }

            HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))

            TextButton(
                onClick = {
                    currentServer?.let {
                        scope.launch {
                            isLoadingChannels = true
                            errorMessage = null
                            try {
                                val fetchedChannels = repository.fetchChannels(it.url, forceRefresh = true)
                                if (fetchedChannels.isEmpty()) {
                                    errorMessage = "No channels found or network error."
                                }
                                channels = fetchedChannels
                            } catch (e: Exception) {
                                errorMessage = "Failed to fetch channels: ${e.localizedMessage}"
                            } finally {
                                isLoadingChannels = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Refresh", fontSize = 14.sp)
            }
        }

        // Center Column: Settings & Filters
        Column(
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight()
                .background(Color(0xFF222222))
                .padding(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = Color.Cyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Settings", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    SettingsToggle(
                        label = "Autoplay First",
                        checked = preferenceManager.myPrefs.cloudAutoplayFirstChannel,
                        onCheckedChange = {
                            preferenceManager.myPrefs.cloudAutoplayFirstChannel = it
                            preferenceManager.savePreferences()
                        }
                    )
                }
                item {
                    SettingsToggle(
                        label = "Dark Mode",
                        checked = preferenceManager.myPrefs.darkMODE,
                        onCheckedChange = {
                            preferenceManager.myPrefs.darkMODE = it
                            preferenceManager.savePreferences()
                        }
                    )
                }
                item {
                    SettingsToggle(
                        label = "Animations",
                        checked = preferenceManager.myPrefs.cloudAnimationEnabled,
                        onCheckedChange = {
                            preferenceManager.myPrefs.cloudAnimationEnabled = it
                            preferenceManager.savePreferences()
                        }
                    )
                }
                item {
                    SettingsToggle(
                        label = "Focus Glow",
                        checked = preferenceManager.myPrefs.cloudFocusAnimationEnabled,
                        onCheckedChange = {
                            preferenceManager.myPrefs.cloudFocusAnimationEnabled = it
                            preferenceManager.savePreferences()
                        }
                    )
                }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Scale", color = Color.White, fontSize = 14.sp)
                        Slider(
                            value = preferenceManager.myPrefs.cloudUiScale,
                            onValueChange = {
                                preferenceManager.myPrefs.cloudUiScale = it
                                preferenceManager.savePreferences()
                            },
                            valueRange = 0.8f..1.2f,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
                item {
                    SettingsToggle(
                        label = "Autoplay Last",
                        checked = preferenceManager.myPrefs.cloudAutoplayLastChannel,
                        onCheckedChange = {
                            preferenceManager.myPrefs.cloudAutoplayLastChannel = it
                            preferenceManager.savePreferences()
                        }
                    )
                }
                item {
                    SettingsActionItem(
                        label = "Search Channels",
                        icon = Icons.Default.Search,
                        onClick = { isSearchVisible = !isSearchVisible }
                    )
                }
                item {
                    SettingsActionItem(
                        label = "Refresh Channels",
                        icon = Icons.Default.Refresh,
                        onClick = {
                            currentServer?.let {
                                scope.launch {
                                    isLoadingChannels = true
                                    errorMessage = null
                                    try {
                                        channels = repository.fetchChannels(it.url, forceRefresh = true)
                                    } catch (e: Exception) {
                                        errorMessage = "Refresh failed: ${e.localizedMessage}"
                                    } finally {
                                        isLoadingChannels = false
                                    }
                                }
                            }
                        }
                    )
                }
                item {
                    SettingsActionItem(
                        label = "View Debug Logs",
                        icon = Icons.Default.BugReport,
                        onClick = {
                            showLogDialog = true
                        }
                    )
                }
                item {
                    SettingsActionItem(
                        label = "Clear Cache",
                        icon = Icons.Default.DeleteSweep,
                        onClick = {
                            repository.clearCache()
                            Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                item {
                    SettingsActionItem(
                        label = "Exit App",
                        icon = Icons.Default.ExitToApp,
                        onClick = { (context as? Activity)?.finishAffinity() }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Filters", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                }

                item {
                    val categories = remember(channels) {
                        channels.mapNotNull { it.group }.distinct().sorted()
                    }
                    SettingsActionItem(
                        label = if (selectedCategories.isEmpty()) "All Categories" else "${selectedCategories.size} Categories",
                        icon = Icons.Default.FilterList,
                        onClick = { showCategoryDialog = true }
                    )
                }

                item {
                    val languages = remember(channels) {
                        channels.mapNotNull { it.language }.distinct().sorted()
                    }
                    SettingsActionItem(
                        label = if (selectedLanguages.isEmpty()) "All Languages" else "${selectedLanguages.size} Languages",
                        icon = Icons.Default.Language,
                        onClick = { showLanguageDialog = true }
                    )
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
                    .width(260.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF2A2A2A))
                    .padding(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search Channels") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.Cyan,
                        unfocusedBorderColor = Color.Gray
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Result count or quick tips
                Text(
                    text = "Found ${filteredChannels.size} channels",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        // Right Column: Channels
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(8.dp)
        ) {
            Text(
                text = currentServer?.name ?: "Channels",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(8.dp)
            )

            if (isLoadingChannels) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.Cyan)
                }
            } else if (errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = errorMessage!!, color = Color.White, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            currentServer?.let {
                                scope.launch {
                                    isLoadingChannels = true
                                    errorMessage = null
                                    channels = repository.fetchChannels(it.url, forceRefresh = true)
                                    isLoadingChannels = false
                                }
                            }
                        }) {
                            Text("Retry")
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredChannels) { channel ->
                        ChannelGridItem(
                            channel = channel,
                            uiScale = preferenceManager.myPrefs.cloudUiScale,
                            focusAnimationEnabled = preferenceManager.myPrefs.cloudFocusAnimationEnabled,
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
            title = "Filter by Category",
            options = categories,
            selectedOptions = selectedCategories,
            onDismiss = { showCategoryDialog = false },
            onConfirm = {
                selectedCategories = it
                preferenceManager.myPrefs.cloudCategoryFilter = it.joinToString(",")
                preferenceManager.savePreferences()
                showCategoryDialog = false
            }
        )
    }

    if (showLanguageDialog) {
        val languages = remember(channels) {
            channels.mapNotNull { it.language }.distinct().sorted()
        }
        MultiSelectFilterDialog(
            title = "Filter by Language",
            options = languages,
            selectedOptions = selectedLanguages,
            onDismiss = { showLanguageDialog = false },
            onConfirm = {
                selectedLanguages = it
                showLanguageDialog = false
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Debug Logs") },
        text = {
            val logs = remember { LogCollector.getLogs() }
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
fun SettingsToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var localChecked by remember(checked) { mutableStateOf(checked) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable {
                val newValue = !localChecked
                localChecked = newValue
                onCheckedChange(newValue)
            }
            .padding(8.dp)
            .background(if (isFocused) Color.White.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(
            checked = localChecked,
            onCheckedChange = {
                localChecked = it
                onCheckedChange(it)
            },
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Cyan)
        )
    }
}

@Composable
fun SettingsActionItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(8.dp)
            .background(if (isFocused) Color.White.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = if (isFocused) Color.Cyan else Color.White, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, color = if (isFocused) Color.Cyan else Color.White, fontSize = 14.sp)
    }
}

@Composable
fun FilterSelector(
    label: String,
    current: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }

    Box(modifier = Modifier.padding(8.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .clickable { expanded = true }
                .background(if (isFocused) Color.White.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Text(label, color = Color.Gray, fontSize = 12.sp)
            Text(current, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF333333))
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, color = Color.White) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun ChannelGridItem(
    channel: CloudChannel,
    uiScale: Float = 1.0f,
    focusAnimationEnabled: Boolean = true,
    onSelected: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused && focusAnimationEnabled) 1.05f * uiScale else 1.0f * uiScale)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(140.dp)
            .scale(scale)
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
                .aspectRatio(16f/9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.DarkGray)
                .border(
                    width = 2.dp,
                    color = if (isFocused) Color.Cyan else Color.Transparent,
                    shape = RoundedCornerShape(12.dp)
                )
        ) {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp)
            )

            // HD Badge (Dummy check for now, can be improved)
            if (channel.name.contains("HD", ignoreCase = true)) {
                Surface(
                    color = Color.Red,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                ) {
                    Text("HD", color = Color.White, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = channel.name,
            color = if (isFocused) Color.Cyan else Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp
        )
    }
}
