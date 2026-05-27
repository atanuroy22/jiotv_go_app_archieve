package com.skylake.skytv.jgorunner.ui.screens

import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import android.app.Activity
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.ExitToApp
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
import com.skylake.skytv.jgorunner.data.CloudServerEntry
import com.skylake.skytv.jgorunner.data.fetchCloudServerCatalog
import com.skylake.skytv.jgorunner.ui.components.MultiSelectFilterDialog
import com.skylake.skytv.jgorunner.ui.tvhome.CloudChannel
import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer
import com.skylake.skytv.jgorunner.utils.LogCollector
import com.skylake.skytv.jgorunner.data.CloudDataManager
import com.skylake.skytv.jgorunner.core.execution.runBinary
import com.skylake.skytv.jgorunner.activities.WebPlayerActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@Composable
fun CloudMainScreen(
    context: Context,
    initialServer: CloudServer?,
    selectedCategory: String?,
    onNavigate: (String) -> Unit,
    onPlayChannel: (CloudChannel, List<CloudChannel>) -> Unit
) {
    val preferenceManager = SkySharedPref.getInstance(context)
    val repository = remember { CloudRepository(context) }
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    var currentServer by remember(initialServer) { mutableStateOf(initialServer) }
    var userSelectedServer by remember { mutableStateOf(false) }
    var serverEntries by remember { mutableStateOf<List<CloudServerEntry>>(emptyList()) }
    var channels by remember { mutableStateOf<List<CloudChannel>>(emptyList()) }
    var isLoadingChannels by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var isSidebarVisible by remember { mutableStateOf(true) }
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val serverFiltersJson = preferenceManager.myPrefs.cloudServerFilters ?: "{}"
    val serverFiltersMap = remember(serverFiltersJson) {
        try {
            val type = object : TypeToken<Map<String, Set<String>>>() {}.type
            gson.fromJson<Map<String, Set<String>>>(serverFiltersJson, type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf<String, Set<String>>()
        }
    }

    val hiddenServersJson = preferenceManager.myPrefs.cloudHiddenServerUrls ?: "[]"
    val hiddenServerUrls = remember(hiddenServersJson) {
        try {
            gson.fromJson<List<String>>(hiddenServersJson, object : TypeToken<List<String>>() {}.type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
    val effectiveHiddenServerUrls = hiddenServerUrls

    var showAllServers by remember {
        mutableStateOf(preferenceManager.myPrefs.cloudShowAllServers)
    }

    val entryByUrl = remember(serverEntries) {
        serverEntries.associateBy { it.server.url }
    }

    val visibleEntries = remember(serverEntries, effectiveHiddenServerUrls) {
        serverEntries.filter { it.server.url !in effectiveHiddenServerUrls }
    }

    val sidebarEntries = remember(visibleEntries, showAllServers, selectedCategory) {
        if (!showAllServers && !selectedCategory.isNullOrBlank()) {
            visibleEntries.filter { it.category.equals(selectedCategory, ignoreCase = true) }
        } else {
            visibleEntries
        }
    }

    var selectedCategories by remember(currentServer) {
        mutableStateOf(serverFiltersMap[currentServer?.url] ?: emptySet())
    }

    var selectedLanguages by remember {
        mutableStateOf(preferenceManager.myPrefs.cloudLanguageFilter?.split(",")?.toSet()?.filter { it.isNotEmpty() }?.toSet() ?: emptySet())
    }

    var showCategoryDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }

    val searchFocusRequester = remember { FocusRequester() }
    val firstChannelFocusRequester = remember { FocusRequester() }

    val filteredChannels = remember(channels, searchQuery, selectedCategories, selectedLanguages) {
        channels.filter { channel ->
            val matchesSearch = searchQuery.isEmpty() ||
                channel.name.contains(searchQuery, ignoreCase = true) ||
                channel.group?.contains(searchQuery, ignoreCase = true) == true ||
                channel.language?.contains(searchQuery, ignoreCase = true) == true

            val matchesCategory = selectedCategories.isEmpty() || selectedCategories.any { filter ->
                channel.group?.contains(filter, ignoreCase = true) == true
            }

            // Fixed: Support multi-language strings in channel.language (e.g., "Hindi, English")
            val matchesLanguage = selectedLanguages.isEmpty() || selectedLanguages.any { filter ->
                channel.language?.contains(filter, ignoreCase = true) == true
            }

            matchesSearch && matchesCategory && matchesLanguage
        }
    }

    LaunchedEffect(filteredChannels) {
        CloudDataManager.currentChannelList = filteredChannels
    }

    LaunchedEffect(Unit) {
        val catalog = fetchCloudServerCatalog(context, repository)
        serverEntries = catalog.entries
    }

    LaunchedEffect(effectiveHiddenServerUrls, serverEntries, selectedCategory, showAllServers) {
        if (visibleEntries.isEmpty()) {
            return@LaunchedEffect
        }
        val filteredPlayableEntries = visibleEntries.filter { !it.isWebTv }
        val categoryEntries = if (!selectedCategory.isNullOrBlank()) {
            visibleEntries.filter { it.category.equals(selectedCategory, ignoreCase = true) }
        } else {
            visibleEntries
        }
        val fallbackWithinCategory = categoryEntries.firstOrNull()?.server
        val anyFallback = filteredPlayableEntries.firstOrNull()?.server
        val activeUrl = currentServer?.url

        val activeStillAvailable = activeUrl != null && visibleEntries.any { it.server.url == activeUrl }

        if (!activeStillAvailable) {
            // If there is a server available within the selected category, prefer that.
            if (fallbackWithinCategory != null) {
                currentServer = fallbackWithinCategory
                userSelectedServer = false
            } else if (!userSelectedServer) {
                // Only fall back to any other server if user didn't explicitly select one.
                currentServer = anyFallback
            }
            // If user selected a server and no category fallback exists, keep the chosen server locked.
        }
    }

    LaunchedEffect(isSearchVisible) {
        if (isSearchVisible) {
            searchFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(filteredChannels, isSidebarVisible, isSearchVisible) {
        if (filteredChannels.isNotEmpty() && !isSidebarVisible && !isSearchVisible) {
            delay(300)
            try {
                firstChannelFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(currentServer) {
        currentServer?.let { server ->
            isSidebarVisible = false
            channels = emptyList()
            isLoadingChannels = true
            errorMessage = null

            val isWebTvServer = entryByUrl[server.url]?.isWebTv == true
            if (isWebTvServer) {
                isLoadingChannels = false
                val intent = Intent(context, WebPlayerActivity::class.java).apply {
                    putExtra("startup_url", server.url)
                }
                context.startActivity(intent)
                return@let
            }

            var retryCount = 0
            val isLocal = server.url.contains("localhost") || server.url.contains("127.0.0.1")

            while (retryCount < 5) {
                try {
                    val fetchedChannels = repository.fetchChannels(server.url)
                    if (fetchedChannels.isNotEmpty()) {
                        channels = fetchedChannels
                        errorMessage = null
                        break
                    } else {
                        if (isLocal) {
                            errorMessage = "Starting local server... (${retryCount + 1})"
                            val activity = context.findActivity() as? ComponentActivity
                            if (activity != null) {
                                runBinary(activity, emptyArray(), {}, {})
                            }
                            delay(10000)
                        } else {
                            errorMessage = "No channels found."
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (isLocal) {
                        errorMessage = "Retrying local server... (${retryCount + 1})"
                        delay(10000)
                    } else {
                        errorMessage = "Failed: ${e.localizedMessage}"
                        break
                    }
                }
                retryCount++
            }

            if (channels.isEmpty() && errorMessage == null) {
                errorMessage = "Failed to load channels."
            }

            isLoadingChannels = false

            // Re-calculate filtered list immediately for autoplay
            val nowFiltered = channels.filter { channel ->
                val matchesCategory = selectedCategories.isEmpty() || selectedCategories.any { filter ->
                    channel.group?.contains(filter, ignoreCase = true) == true
                }
                val matchesLanguage = selectedLanguages.isEmpty() || selectedLanguages.any { filter ->
                    channel.language?.contains(filter, ignoreCase = true) == true
                }
                matchesCategory && matchesLanguage
            }

            if (nowFiltered.isNotEmpty()) {
                val serverLastPlayedMapJson = preferenceManager.myPrefs.lastCloudPlayedChannelId ?: "{}"
                val lastPlayedMap: Map<String, String> = try {
                    gson.fromJson(serverLastPlayedMapJson, object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
                } catch (_: Exception) { emptyMap() }

                val lastId = if (preferenceManager.myPrefs.cloudAutoplayLastChannel) lastPlayedMap[server.url] else null
                val lastChannel = if (lastId != null) nowFiltered.find { it.id == lastId } else null

                if (lastChannel != null) {
                    onPlayChannel(lastChannel, nowFiltered)
                } else if (preferenceManager.myPrefs.cloudAutoplayFirstChannel) {
                    onPlayChannel(nowFiltered.first(), nowFiltered)
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
            .background(Color(0xFF050505))
    ) {
        // Sidebar
        val animationsEnabled = preferenceManager.myPrefs.cloudAnimationEnabled

        AnimatedVisibility(
            visible = isSidebarVisible,
            enter = if (animationsEnabled) expandHorizontally() + fadeIn() else EnterTransition.None,
            exit = if (animationsEnabled) shrinkHorizontally() + fadeOut() else ExitTransition.None
        ) {
            Row(modifier = Modifier.fillMaxHeight()) {
                // Servers
                Column(
                    modifier = Modifier
                        .width(160.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF101010))
                        .padding(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        IconButton(onClick = { isSidebarVisible = false }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, "Collapse", tint = Color.Red, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Servers", color = Color.Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        val subExpiry = preferenceManager.myPrefs.cloudSubExpiry
                        val isSubscribed = subExpiry > System.currentTimeMillis()

                        LazyColumn {
                            items(sidebarEntries.map { it.server }) { server ->
                                val isLocal = server.url.contains("localhost") || server.url.contains("127.0.0.1")
                                if (isSubscribed || isLocal) {
                                    ServerListItem(
                                        server = server,
                                        isSelected = server.url == currentServer?.url,
                                        onSelected = {
                                            val isWebTv = entryByUrl[server.url]?.isWebTv == true
                                            if (isWebTv) {
                                                val intent = Intent(context, WebPlayerActivity::class.java).apply {
                                                    putExtra("startup_url", server.url)
                                                }
                                                context.startActivity(intent)
                                            } else {
                                                currentServer = server
                                                userSelectedServer = true
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Settings
                Column(
                    modifier = Modifier
                        .width(180.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF181818))
                        .padding(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        Text("Settings", color = Color.Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { isSidebarVisible = false }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, "Collapse", tint = Color.Red, modifier = Modifier.size(18.dp))
                        }
                    }
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        item {
                            var checked by remember { mutableStateOf(preferenceManager.myPrefs.cloudAutoplayFirstChannel) }
                            SettingsToggleRefreshed("Autoplay 1st CH", checked) {
                                checked = it
                                preferenceManager.myPrefs.cloudAutoplayFirstChannel = it
                                preferenceManager.savePreferences()
                            }
                        }
                        item {
                            var checked by remember { mutableStateOf(preferenceManager.myPrefs.cloudAutoplayLastChannel) }
                            SettingsToggleRefreshed("Autoplay Last played CH", checked) {
                                checked = it
                                preferenceManager.myPrefs.cloudAutoplayLastChannel = it
                                preferenceManager.savePreferences()
                            }
                        }
                        item {
                            var checked by remember { mutableStateOf(preferenceManager.myPrefs.cloudAnimationEnabled) }
                            SettingsToggleRefreshed("Animations", checked) {
                                checked = it
                                preferenceManager.myPrefs.cloudAnimationEnabled = it
                                preferenceManager.savePreferences()
                            }
                        }
                        item {
                            var checked by remember { mutableStateOf(preferenceManager.myPrefs.cloudFocusAnimationEnabled) }
                            SettingsToggleRefreshed("Focus Glow", checked) {
                                checked = it
                                preferenceManager.myPrefs.cloudFocusAnimationEnabled = it
                                preferenceManager.savePreferences()
                            }
                        }
                        item {
                            var checked by remember(showAllServers) { mutableStateOf(showAllServers) }
                            SettingsToggleRefreshed("Show All Servers", checked) {
                                checked = it
                                showAllServers = it
                                preferenceManager.myPrefs.cloudShowAllServers = it
                                preferenceManager.savePreferences()
                            }
                        }
                        item {
                            SettingsActionItemCompact("Search", Icons.Default.Search) { isSearchVisible = !isSearchVisible }
                        }
                        item {
                            SettingsActionItemCompact("Category Filter", Icons.Default.FilterList) { showCategoryDialog = true }
                        }
                        item {
                            SettingsActionItemCompact("Language Filter", Icons.Default.Language) { showLanguageDialog = true }
                        }
                        item {
                            SettingsActionItemCompact("Clear Filters", Icons.Default.FilterAltOff) {
                                selectedCategories = emptySet()
                                selectedLanguages = emptySet()
                                val newMap = serverFiltersMap.toMutableMap()
                                if (currentServer != null) {
                                    newMap[currentServer!!.url] = emptySet()
                                }
                                preferenceManager.myPrefs.cloudServerFilters = gson.toJson(newMap)
                                preferenceManager.myPrefs.cloudLanguageFilter = ""
                                preferenceManager.savePreferences()
                            }
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
                            SettingsActionItemCompact("Reset Settings", Icons.Default.RestartAlt) {
                                preferenceManager.myPrefs.cloudAutoplayFirstChannel = false
                                preferenceManager.myPrefs.cloudAutoplayLastChannel = true
                                preferenceManager.myPrefs.cloudAnimationEnabled = true
                                preferenceManager.myPrefs.cloudFocusAnimationEnabled = true
                                preferenceManager.myPrefs.cloudShowAllServers = false
                                preferenceManager.myPrefs.cloudServerFilters = "{}"
                                preferenceManager.myPrefs.cloudLanguageFilter = ""
                                preferenceManager.myPrefs.cloudCategoryFilter = null
                                preferenceManager.myPrefs.cloudUiScale = 1.0f
                                preferenceManager.myPrefs.filterQX = null
                                preferenceManager.savePreferences()
                                showAllServers = false
                                Toast.makeText(context, "Settings reset", Toast.LENGTH_SHORT).show()
                            }
                        }
                        item {
                            SettingsActionItemCompact("Exit", Icons.AutoMirrored.Filled.ExitToApp) { (context as? Activity)?.finishAffinity() }
                        }
                    }
                }
            }
        }

        // Search Panel
        AnimatedVisibility(
            visible = isSearchVisible,
            enter = if (animationsEnabled) expandHorizontally() + fadeIn() else EnterTransition.None,
            exit = if (animationsEnabled) shrinkHorizontally() + fadeOut() else ExitTransition.None
        ) {
            Column(
                modifier = Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF202020))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Search Channels", color = Color.Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { isSearchVisible = false }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = Color.Red, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Cyan),
                    singleLine = true,
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.AutoMirrored.Filled.Backspace, "Clear", tint = Color.Gray, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { searchQuery = "" },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Clear", fontSize = 11.sp)
                    }
                }

                Text("Results: ${filteredChannels.size}", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }

        // Channels
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                if (!isSidebarVisible) {
                    IconButton(onClick = { isSidebarVisible = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Menu, "Expand", tint = Color.Cyan, modifier = Modifier.size(24.dp))
                    }
                }
                Text(
                    text = currentServer?.name ?: "Channels",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                if (selectedCategories.isNotEmpty() || selectedLanguages.isNotEmpty()) {
                    Surface(
                        color = Color.Cyan.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.clickable { showCategoryDialog = true }
                    ) {
                        Text(
                            text = "${selectedCategories.size + selectedLanguages.size} filters",
                            color = Color.Cyan,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (visibleEntries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "All servers are hidden. Open CloudHome settings to unhide.",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            } else if (isLoadingChannels) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.Cyan)
                        if (errorMessage != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(text = errorMessage!!, color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            } else if (errorMessage != null && channels.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = errorMessage!!, color = Color.White)
                        Button(onClick = { onNavigate("CloudHome") }) { Text("Retry") }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 80.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(filteredChannels) { index, channel ->
                        ChannelGridItemCompact(
                            channel = channel,
                            focusAnimEnabled = preferenceManager.myPrefs.cloudFocusAnimationEnabled,
                            modifier = if (index == 0) Modifier.focusRequester(firstChannelFocusRequester) else Modifier,
                            onSelected = {
                                onPlayChannel(channel, filteredChannels)
                            }
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

    if (showLanguageDialog) {
        // Fixed: Ensure available languages include all detected from channels
        val defaultLangs = listOf("Hindi", "English", "Tamil", "Telugu", "Malayalam", "Kannada", "Bengali", "Marathi", "Gujarati", "Punjabi", "Urdu", "Odia", "Assamese")
        val availableLangs = remember(channels) {
            val detected = channels.flatMap { it.language?.split(",")?.map { l -> l.trim() } ?: emptyList() }
            (detected + defaultLangs).filter { it.isNotEmpty() }.distinct().sorted()
        }
        MultiSelectFilterDialog(
            title = "Languages",
            options = availableLangs,
            selectedOptions = selectedLanguages,
            onDismiss = { showLanguageDialog = false },
            onConfirm = {
                selectedLanguages = it
                preferenceManager.myPrefs.cloudLanguageFilter = it.joinToString(",")
                preferenceManager.savePreferences()
                showLanguageDialog = false
            }
        )
    }

    if (showLogDialog) {
        LogViewerDialog(
            onDismiss = { showLogDialog = false },
            onCopy = { LogCollector.copyToClipboard(context) },
            onClear = {
                LogCollector.clear()
                Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun ServerListItem(
    server: CloudServer,
    isSelected: Boolean,
    onSelected: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.05f else 1.0f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 2.dp)
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) Color.Cyan.copy(alpha = 0.2f) else if (isSelected) Color.DarkGray else Color.Transparent)
            .border(
                width = 2.dp,
                color = if (isFocused) Color.Cyan else if (isSelected) Color.Gray else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onSelected() }
            .focusable()
            .padding(8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = server.logo,
                contentDescription = null,
                modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = server.name,
                color = if (isFocused || isSelected) Color.White else Color.Gray,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ChannelGridItemCompact(
    channel: CloudChannel,
    focusAnimEnabled: Boolean,
    modifier: Modifier = Modifier,
    onSelected: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused && focusAnimEnabled) 1.1f else 1.0f)

    Box(
        modifier = modifier
            .width(130.dp)
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.1f) else Color.Transparent)
            .border(
                width = 2.dp,
                color = if (isFocused) Color.Cyan else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onSelected() }
            .focusable()
            .padding(4.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = null,
                    modifier = Modifier
                        .size(110.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.05f)),
                    contentScale = ContentScale.Fit
                )

                if (channel.name.contains("HD", ignoreCase = true)) {
                    Surface(
                        color = Color.Red,
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)
                    ) {
                        Text("HD", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 2.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = channel.name,
                color = Color.White,
                fontSize = 16.sp,
                maxLines = 2,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun SettingsToggleRefreshed(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(6.dp))
            .background(if (isFocused) Color.Cyan.copy(alpha = 0.15f) else Color.Transparent)
            .clickable { onCheckedChange(!checked) }
            .focusable()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (isFocused) Color.White else Color.Gray, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.scale(0.7f),
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Cyan)
        )
    }
}

@Composable
fun SettingsActionItemCompact(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(6.dp))
            .background(if (isFocused) Color.Cyan.copy(alpha = 0.15f) else Color.Transparent)
            .clickable { onClick() }
            .focusable()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (isFocused) Color.Cyan else Color.Gray, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, color = if (isFocused) Color.White else Color.Gray, fontSize = 11.sp)
    }
}

@Composable
fun LogViewerDialog(onDismiss: () -> Unit, onCopy: () -> Unit, onClear: () -> Unit) {
    var refreshTick by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App Logs", fontSize = 16.sp, color = Color.Cyan) },
        text = {
            val logs = remember(refreshTick) { LogCollector.getLogs() }
            Box(modifier = Modifier.height(300.dp).fillMaxWidth().background(Color.Black).padding(8.dp)) {
                val scrollState = rememberScrollState()
                Text(
                    text = if (logs.isBlank()) "No logs yet." else logs,
                    color = Color.Green,
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(scrollState)
                )
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                IconButton(
                    onClick = {
                        onClear()
                        refreshTick++
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear logs",
                        tint = Color.Cyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onCopy) { Text("Copy") }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = null,
        containerColor = Color(0xFF1A1A1A),
        textContentColor = Color.White,
        titleContentColor = Color.White
    )
}

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}