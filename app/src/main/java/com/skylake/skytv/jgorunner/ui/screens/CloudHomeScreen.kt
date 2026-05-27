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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.skylake.skytv.jgorunner.data.CloudRepository
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.data.CloudServerEntry
import com.skylake.skytv.jgorunner.data.fetchCloudServerCatalog
import com.skylake.skytv.jgorunner.ui.components.MultiSelectFilterDialog
import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

private const val REMOTE_ACCESS_KEY_URL = "https://raw.githubusercontent.com/atanuroy22/j/refs/heads/main/j"

private val accessKeyHttpClient = OkHttpClient()
private const val KEY_VALIDATION_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes

private data class ServerCategoryCard(
    val name: String,
    val logo: String,
    val entries: List<CloudServerEntry>
)

@Composable
fun CloudHomeScreen(
    context: Context,
    onCategorySelected: (String, CloudServer?) -> Unit,
    onNavigate: (String) -> Unit
) {
    val preferenceManager = SkySharedPref.getInstance(context)
    val repository = remember { CloudRepository(context) }
    val gson = remember { Gson() }
    val scope = rememberCoroutineScope()

    var allServers by remember { mutableStateOf<List<CloudServer>>(emptyList()) }
    var visibleServers by remember { mutableStateOf<List<CloudServer>>(emptyList()) }
    var categoryCards by remember { mutableStateOf<List<ServerCategoryCard>>(emptyList()) }
    var entryByUrl by remember { mutableStateOf<Map<String, CloudServerEntry>>(emptyMap()) }
    var countdown by remember { mutableIntStateOf(5) }
    var isAutoopenActive by remember { mutableStateOf(false) }
    var autoopenConsumed by rememberSaveable { mutableStateOf(false) }
    var showCouponDialog by remember { mutableStateOf(false) }
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showHiddenServersDialog by remember { mutableStateOf(false) }
    var showAutoopenServerDialog by remember { mutableStateOf(false) }
    var showOfferDialog by remember { mutableStateOf(false) }

    var refreshTrigger by remember { mutableIntStateOf(0) }

    val subExpiry = remember(refreshTrigger) { preferenceManager.myPrefs.cloudSubExpiry }
    val isSubscribed = remember(subExpiry, refreshTrigger) {
        subExpiry > System.currentTimeMillis() && preferenceManager.myPrefs.cloudAccessKeyValid
    }

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
    val storedAccessKey = preferenceManager.myPrefs.cloudAccessKey?.trim().orEmpty()

    val focusRequester = remember { FocusRequester() }
    val serverFocusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }

    suspend fun syncAccessKeyWithGithub() {
        val now = System.currentTimeMillis()
        val lastCheck = preferenceManager.myPrefs.cloudLastKeyValidation
        if (lastCheck > 0 && (now - lastCheck) < KEY_VALIDATION_INTERVAL_MS) {
            // Throttle: skip network validation if last check was within interval
            return
        }
        if (storedAccessKey.isBlank()) {
            if (preferenceManager.myPrefs.cloudAccessKeyValid) {
                preferenceManager.myPrefs.cloudAccessKeyValid = false
                preferenceManager.savePreferences()
                refreshTrigger++
            }
            return
        }

        var remoteKeys: Set<String>? = null
        repeat(2) {
            remoteKeys = withContext(Dispatchers.IO) {
                fetchRemoteAccessKeys()
            }
            if (remoteKeys != null) return@repeat
            delay(600)
        }

        if (remoteKeys != null && storedAccessKey !in remoteKeys) {
            preferenceManager.myPrefs.cloudAccessKey = null
            preferenceManager.myPrefs.cloudSubExpiry = 0L
            preferenceManager.myPrefs.cloudAccessKeyValid = false
            preferenceManager.savePreferences()
            refreshTrigger++
            Toast.makeText(context, "Access key removed", Toast.LENGTH_SHORT).show()
        } else if (remoteKeys != null && storedAccessKey in remoteKeys) {
            if (!preferenceManager.myPrefs.cloudAccessKeyValid) {
                preferenceManager.myPrefs.cloudAccessKeyValid = true
                preferenceManager.savePreferences()
                refreshTrigger++
            }
        } else {
            if (preferenceManager.myPrefs.cloudAccessKeyValid) {
                preferenceManager.myPrefs.cloudAccessKeyValid = false
                preferenceManager.savePreferences()
                refreshTrigger++
            }
        }
        // Update last validation timestamp to avoid frequent network checks
        preferenceManager.myPrefs.cloudLastKeyValidation = System.currentTimeMillis()
        preferenceManager.savePreferences()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, storedAccessKey) {
        scope.launch {
            syncAccessKeyWithGithub()
        }

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    syncAccessKeyWithGithub()
                }
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Small pulsing indicator for the offer arrow when unsubscribed
    var offerPulse by remember { mutableStateOf(false) }
    LaunchedEffect(isSubscribed) {
        if (!isSubscribed) {
            while (true) {
                offerPulse = true
                delay(650)
                offerPulse = false
                delay(650)
            }
        } else {
            offerPulse = false
        }
    }

    LaunchedEffect(refreshTrigger) {
        val catalog = fetchCloudServerCatalog(context, repository)
        val entries = catalog.entries
        entryByUrl = catalog.byUrl
        val baseList = entries.map { it.server }.distinctBy { it.url }
        val sportsUrls = entries
            .filter { it.category.contains("sport", ignoreCase = true) }
            .map { it.server.url }
            .toSet()

        var currentHiddenUrls = hiddenServerUrls.toMutableList()
        
        // Set default hidden servers on first run
        if (hiddenServerUrls.isEmpty() && isSubscribed && baseList.isNotEmpty()) {
            currentHiddenUrls = baseList
                .filter { server ->
                    // Keep JioTV+, Free Jio, Sony (IN), Tata Bing, and Jio Crystal visible.
                    // Hide everything else and slow/unresponsive servers.
                    val isJio = server.name.contains("jio", ignoreCase = true) && !server.name.contains("sony", ignoreCase = true)
                    val isSonyIn = server.name.contains("sony", ignoreCase = true) && server.name.contains("in", ignoreCase = true)
                    val isSlow = server.name.contains("slow", ignoreCase = true)
                    val isZeeSd = server.name.contains("zee", ignoreCase = true) &&
                        server.name.contains("sd", ignoreCase = true)
                    val isTataBing = server.name.contains("tata bing", ignoreCase = true)
                    val isJioCrystal = server.name.contains("jio crystal", ignoreCase = true)
                    val isSportsServer = server.url in sportsUrls
                    !(isJio || isSonyIn || isZeeSd || isTataBing || isJioCrystal || isSportsServer) || (isSlow && !isJio)
                }
                .map { it.url }
                .toMutableList()
            
            if (currentHiddenUrls.isNotEmpty()) {
                preferenceManager.myPrefs.cloudHiddenServerUrls = gson.toJson(currentHiddenUrls)
                preferenceManager.savePreferences()
            }
        }
        
        val visibleEntries = entries.filter { it.server.url !in currentHiddenUrls }
        val visiblePlayableServers = visibleEntries
            .filter { !it.isWebTv }
            .map { it.server }
        val orderedCategories = entries.map { it.category }.distinct()
        val cards = orderedCategories.mapNotNull { category ->
            val categoryEntries = visibleEntries.filter { it.category == category }
            if (categoryEntries.isEmpty()) return@mapNotNull null
            val logo = categoryEntries.firstOrNull { it.server.logo.isNotBlank() }?.server?.logo ?: ""
            ServerCategoryCard(name = category, logo = logo, entries = categoryEntries)
        }

        allServers = baseList
        visibleServers = visiblePlayableServers
        categoryCards = cards

        if (visiblePlayableServers.isNotEmpty()) {
            val resolvedAutoopenUrl = when {
                !isSubscribed -> visiblePlayableServers.first().url
                autoopenServerUrl != null && visiblePlayableServers.any { it.url == autoopenServerUrl } -> autoopenServerUrl
                else -> visiblePlayableServers.first().url
            }
            if (autoopenServerUrl != resolvedAutoopenUrl) {
                autoopenServerUrl = resolvedAutoopenUrl
                preferenceManager.myPrefs.cloudAutoplayServerUrl = resolvedAutoopenUrl
                preferenceManager.savePreferences()
            }
        }
    }

    LaunchedEffect(autoopenDelaySeconds, visibleServers, autoopenServerUrl, autoopenConsumed) {
        if (!autoopenConsumed && autoopenDelaySeconds > 0 && visibleServers.isNotEmpty()) {
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
                    visibleServers.firstOrNull { it.url == autoopenServerUrl } ?: visibleServers.firstOrNull()
                } else {
                    visibleServers.firstOrNull {
                        it.name.contains("free jio", ignoreCase = true) ||
                            it.url.contains("localhost", ignoreCase = true) ||
                            it.url.contains("127.0.0.1")
                    } ?: visibleServers.firstOrNull()
                }
                if (target != null) {
                    val category = entryByUrl[target.url]?.category ?: "JioTV+"
                    onCategorySelected(category, target)
                }
            }
        } else {
            isAutoopenActive = false
        }
    }

    LaunchedEffect(hiddenServerUrls, autoopenServerUrl) {
        if (autoopenServerUrl != null && autoopenServerUrl in hiddenServerUrls) {
            autoopenServerUrl = visibleServers.firstOrNull()?.url ?: autoopenServerUrl
            preferenceManager.myPrefs.cloudAutoplayServerUrl = autoopenServerUrl
            preferenceManager.savePreferences()
        }
    }

    BackHandler(enabled = showOfferDialog) {
        showOfferDialog = false
    }

    BackHandler(enabled = !showOfferDialog) {
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
                    text = "Cloud UI",
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

            if (categoryCards.isEmpty()) {
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
                    items(categoryCards) { card ->
                        val itemFocusRequester = serverFocusRequesters.getOrPut(card.name) { FocusRequester() }
                        ServerCard(
                            title = card.name,
                            logoUrl = card.logo,
                            modifier = Modifier.focusRequester(itemFocusRequester),
                            onSelected = {
                                isAutoopenActive = false
                                autoopenConsumed = true
                                onCategorySelected(card.name, null)
                            }
                        )
                    }
                }

                LaunchedEffect(Unit) {
                    delay(500)
                    try { focusRequester.requestFocus() } catch(_: Exception) {}
                }
            }

            if (isAutoopenActive && visibleServers.isNotEmpty()) {
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
                    "Special Offer"
                }

                Column(modifier = Modifier.weight(1f)) {
                    if (isSubscribed) {
                        Text(
                            text = expiryText,
                            color = Color.Green,
                            fontSize = 11.sp
                        )
                    } else {
                        val scale = animateFloatAsState(if (offerPulse) 1.15f else 1.0f)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            // Make the left area clickable so users notice the offer even if text is long
                            Row(modifier = Modifier.fillMaxWidth().clickable { showOfferDialog = true }.focusable(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = expiryText,
                                    color = Color.Yellow,
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                // Small label to attract attention
                                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF0F1720)) {
                                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.LocalOffer, contentDescription = null, tint = Color.Cyan, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Offers", color = Color.Cyan, fontSize = 11.sp)
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = Color.Cyan, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            DropdownMenu(
                                expanded = showOfferDialog,
                                onDismissRequest = { showOfferDialog = false },
                                modifier = Modifier.widthIn(min = 250.dp, max = 340.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Special Offer — Jio+ & Sports", color = Color.Cyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(8.dp))
                                    Text("Includes: JioTV+, Sony, Zee, Sports, Tata Play", color = Color.White, fontSize = 13.sp)
                                    Spacer(Modifier.height(6.dp))
                                    Text("• 1 week — ₹10", color = Color.White, fontSize = 13.sp)
                                    Text("• 1 month — ₹40", color = Color.White, fontSize = 13.sp)
                                    Spacer(Modifier.height(12.dp))

                                    Text("Steps to Activate:", color = Color.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(6.dp))
                                    Text("1. Pay the amount through UPI.", color = Color.White, fontSize = 12.sp)
                                    Text("2. Send the screenshot to Telegram.", color = Color.White, fontSize = 12.sp)
                                    Text("3. New key will be sent to your Telegram.", color = Color.White, fontSize = 12.sp)

                                    androidx.compose.material3.HorizontalDivider(
                                        color = Color.Gray.copy(alpha = 0.3f),
                                        modifier = Modifier.padding(vertical = 12.dp)
                                    )

                                    Text(
                                        "Note: You're accessing with less price, so don't compare with the original app features and stability.",
                                        color = Color.Gray,
                                        fontSize = 10.sp
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row {
                                        TextButton(onClick = {
                                            try {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                val clip = android.content.ClipData.newPlainText("UPI", "atanukrroy15-1@okicici")
                                                clipboard.setPrimaryClip(clip)
                                                Toast.makeText(context, "UPI copied", Toast.LENGTH_SHORT).show()
                                            } catch (_: Exception) {}
                                        }) { Text("Copy UPI", color = Color.Cyan) }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        TextButton(onClick = {
                                            try {
                                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/atanu_roy"))
                                                context.startActivity(intent)
                                            } catch (_: Exception) {}
                                        }) { Text("Open Telegram", color = Color.Cyan) }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        TextButton(onClick = { showOfferDialog = false }) { Text("Close") }
                                    }
                                }
                            }
                        }
                    }
                    val autoopenName = allServers.firstOrNull { it.url == autoopenServerUrl }?.name ?: visibleServers.firstOrNull()?.name ?: ""
                    val hiddenCount = hiddenServerUrls.size
                    Text(
                        text = "AutoOpen: $autoopenName | Hidden: $hiddenCount",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }

                if (isSubscribed) {
                    TextButton(onClick = {
                        preferenceManager.myPrefs.cloudAccessKey = null
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
                scope.launch {
                    val validity = validateKeyWithRemote(key)
                    if (validity != null) {
                        preferenceManager.myPrefs.cloudAccessKey = key.trim()
                        preferenceManager.myPrefs.cloudSubExpiry = validity
                        preferenceManager.savePreferences()
                        Toast.makeText(context, "Key applied!", Toast.LENGTH_LONG).show()
                        showCouponDialog = false
                        refreshTrigger++
                    } else {
                        Toast.makeText(context, "Invalid key or remote check failed!", Toast.LENGTH_SHORT).show()
                    }
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
                        val autoopenName = visibleServers.firstOrNull { it.url == autoopenServerUrl }?.name ?: visibleServers.firstOrNull()?.name ?: "Auto"
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
                                preferenceManager.myPrefs.cloudAutoplayDelaySeconds = 15
                                preferenceManager.myPrefs.cloudAutoplayFirstChannel = true
                                preferenceManager.myPrefs.cloudAutoplayLastChannel = true
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
                                preferenceManager.myPrefs.cloudShowAllServers = false
                                preferenceManager.myPrefs.filterQX = null
                                preferenceManager.savePreferences()
                                autoopenServerUrl = null
                                autoopenDelaySeconds = 15
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
        val optionPairs = visibleServers.mapIndexed { index, server -> "${index + 1}. ${server.name}" to server.url }
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
    title: String,
    logoUrl: String,
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
            model = logoUrl,
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
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

private suspend fun fetchRemoteAccessKeys(): Set<String>? = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(REMOTE_ACCESS_KEY_URL).build()

    runCatching {
        accessKeyHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null

            val remoteEncodedKeys = response.body?.string().orEmpty()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()

            if (remoteEncodedKeys.isEmpty()) return@withContext emptySet()

            remoteEncodedKeys.mapNotNull { encodedKey ->
                runCatching {
                    String(
                        android.util.Base64.decode(encodedKey, android.util.Base64.DEFAULT),
                        Charsets.UTF_8
                    ).trim()
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }.toSet()
        }
    }.getOrNull()
}

private suspend fun validateKeyWithRemote(rawKey: String): Long? {
    val localValidity = validateKey(rawKey) ?: return null
    val cleanKey = rawKey.trim()
    val remoteKeys = fetchRemoteAccessKeys() ?: return null

    return if (cleanKey in remoteKeys) localValidity else null
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