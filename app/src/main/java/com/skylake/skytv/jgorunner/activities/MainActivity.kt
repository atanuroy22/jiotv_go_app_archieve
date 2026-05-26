package com.skylake.skytv.jgorunner.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.skylake.skytv.jgorunner.BuildConfig
import com.skylake.skytv.jgorunner.core.checkServerStatus
import com.skylake.skytv.jgorunner.core.execution.runBinary
import com.skylake.skytv.jgorunner.core.execution.stopBinary
import com.skylake.skytv.jgorunner.core.update.ApplicationUpdater
import com.skylake.skytv.jgorunner.core.update.BinaryUpdater
import com.skylake.skytv.jgorunner.core.update.DownloadModelNew
import com.skylake.skytv.jgorunner.core.update.DownloadProgress
import com.skylake.skytv.jgorunner.core.update.SemanticVersionNew
import com.skylake.skytv.jgorunner.core.update.Status
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.services.BinaryService
import com.skylake.skytv.jgorunner.services.player.LandingPage
import com.skylake.skytv.jgorunner.ui.components.BottomNavigationBar
import com.skylake.skytv.jgorunner.ui.components.CustPopup
import com.skylake.skytv.jgorunner.ui.components.JTVModeSelectorPopup
import com.skylake.skytv.jgorunner.ui.components.LoginPopup
import com.skylake.skytv.jgorunner.ui.components.ProgressPopup
import com.skylake.skytv.jgorunner.ui.components.RedirectPopup
import com.skylake.skytv.jgorunner.ui.screens.*
import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer
import com.skylake.skytv.jgorunner.ui.theme.JGOTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "JTVGo::MainActivity"
    }

    private var selectedBinaryName by mutableStateOf("JioTV+")
    private lateinit var preferenceManager: SkySharedPref

    private var outputText by mutableStateOf("ℹ️ Output logs")
    private var currentScreen by mutableStateOf("CloudHome")

    private val executor = Executors.newSingleThreadExecutor()
    private var showBinaryUpdatePopup by mutableStateOf(false)
    private var showAppUpdatePopup by mutableStateOf(false)
    private var showLoginPopup by mutableStateOf(false)
    private var showLoginPopupD by mutableStateOf(false)
    private var isServerRunning by mutableStateOf(false)
    private var isGlowBox by mutableStateOf(false)
    private var showOverlayPermissionPopup by mutableStateOf(false)
    private var showRedirectPopup by mutableStateOf(false)
    private var shouldLaunchIPTV by mutableStateOf(false)
    private var countdownJob: Job? = null
    private var autoServerMonitorJob: Job? = null
    private var downloadProgress by mutableStateOf<DownloadProgress?>(null)
    private var isSwitchOnForAutoStartForeground by mutableStateOf(false)
    private var showOperationDialog by mutableStateOf(false)
    private var isSwitchDarkMode by mutableStateOf(false)
    private var serverStartRequestedAt by mutableStateOf(0L)

    private var selectedServer by mutableStateOf<CloudServer?>(null)
    private var selectedServerCategory by mutableStateOf<String?>(null)

    override fun onStart() {
        super.onStart()
        runOnceAfterAppUpgrade()
        preferenceManager = SkySharedPref.getInstance(this)

        val rawPrefs = getSharedPreferences("SkySharedPref", MODE_PRIVATE)
        if (!rawPrefs.contains("auto_start_server")) {
            preferenceManager.myPrefs.autoStartServer = true
            preferenceManager.savePreferences()
        }

        BinaryUpdater.init(this)
        startAutoServerMonitor()

        val appPackageName = preferenceManager.myPrefs.iptvAppPackageName
        val isTvZoneSelected = appPackageName.equals("tvzone", ignoreCase = true)

        if (isTvZoneSelected) {
            preferenceManager.myPrefs.autoStartIPTV = false
        }

        if (preferenceManager.myPrefs.jtvGoBinaryVersion?.contains("develop", ignoreCase = true) == true) {
            preferenceManager.myPrefs.preRelease = true
        } else {
            preferenceManager.myPrefs.preRelease = false
        }
        preferenceManager.savePreferences()

        if (preferenceManager.myPrefs.iptvLaunchCountdown == 0) {
            preferenceManager.myPrefs.iptvLaunchCountdown = 4
            preferenceManager.myPrefs.enableAutoUpdate = true
            preferenceManager.myPrefs.loginChk = true
            preferenceManager.myPrefs.jtvGoServerPort = 5350
            preferenceManager.myPrefs.jtvGoBinaryVersion = "v0.0.0"
            preferenceManager.savePreferences()
        }

        isServerRunning = BinaryService.isRunning

        if (isServerRunning) {
            BinaryService.instance?.binaryOutput?.observe(this) {
                outputText = it
            }
        }

        val shouldAutoStartServer = preferenceManager.myPrefs.autoStartServer || preferenceManager.myPrefs.startTvAutomatically
        if (shouldAutoStartServer) {
            runBinary(
                activity = this,
                arguments = emptyArray(),
                onRunSuccess = { onJTVServerRun() },
                onOutput = { output -> outputText = output },
                forceStart = false
            )
        }
    }

    private fun startAutoServerMonitor() {
        if (autoServerMonitorJob?.isActive == true) return
        autoServerMonitorJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val shouldAutoStartServer = preferenceManager.myPrefs.autoStartServer || preferenceManager.myPrefs.startTvAutomatically
                if (!shouldAutoStartServer) {
                    delay(12_000L)
                    continue
                }
                if (!BinaryService.isRunning) {
                    runBinary(
                        activity = this@MainActivity,
                        arguments = emptyArray(),
                        onRunSuccess = { onJTVServerRun() },
                        onOutput = { output -> Log.d(TAG, output) },
                        forceStart = false
                    )
                }
                delay(12_000L)
            }
        }
    }

    private fun runOnceAfterAppUpgrade() {
        val skySharedPref = SkySharedPref.getInstance(this)
        val prefs = getSharedPreferences("app_update_prefs", MODE_PRIVATE)
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val currentVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
        val lastVersion = prefs.getLong("last_version_code", -1L)

        if (currentVersion > lastVersion) {
            val backupPrefs = skySharedPref.myPrefs.copy()
            skySharedPref.clearPreferences()
            skySharedPref.myPrefs = backupPrefs
            skySharedPref.savePreferences()
            prefs.edit { putLong("last_version_code", currentVersion) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isServerRunning) onJTVServerRun()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissions()
        preferenceManager = SkySharedPref.getInstance(this)
        isSwitchDarkMode = preferenceManager.myPrefs.darkMODE
        isSwitchOnForAutoStartForeground = preferenceManager.myPrefs.autoStartOnBootForeground

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
        }

        val filter = IntentFilter(BinaryService.ACTION_BINARY_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(binaryStoppedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(binaryStoppedReceiver, filter)
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        setContent {
            JGOTheme(themeOverride = isSwitchDarkMode) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (currentScreen != "CloudPlayer") {
                            BottomNavigationBar(
                                currentScreen = currentScreen,
                                setCurrentScreen = { currentScreen = it }
                            )
                        }
                    },
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        when (currentScreen) {
                            "CloudHome" -> CloudHomeScreen(
                                context = this@MainActivity,
                                onCategorySelected = { category, preferredServer ->
                                    selectedServerCategory = category
                                    selectedServer = preferredServer
                                    currentScreen = "CloudMain"
                                },
                                onNavigate = { currentScreen = it }
                            )
                            "CloudMain" -> CloudMainScreen(
                                context = this@MainActivity,
                                initialServer = selectedServer,
                                selectedCategory = selectedServerCategory,
                                onNavigate = { currentScreen = it },
                                onPlayChannel = { channel, list ->
                                    val intent = Intent(this@MainActivity, CloudPlayerActivity::class.java).apply {
                                        putExtra("current_cloud_channel_index", list.indexOf(channel))
                                        putExtra("server_url", selectedServer?.url)
                                    }
                                    startActivity(intent)
                                }
                            )
                            "JioHome" -> HomeScreen(
                                title = selectedBinaryName,
                                titleShouldGlow = isGlowBox,
                                isServerRunning = isServerRunning,
                                publicJTVServerURL = getPublicJTVServerURL(this@MainActivity),
                                outputText = outputText,
                                onRunServerButtonClick = {
                                    runBinary(
                                        activity = this@MainActivity,
                                        arguments = emptyArray(),
                                        onRunSuccess = { onJTVServerRun() },
                                        onOutput = { output -> outputText = output }
                                    )
                                },
                                onStopServerButtonClick = {
                                    stopBinary(this@MainActivity) {
                                        isGlowBox = false
                                        isServerRunning = false
                                        outputText = "Server stopped"
                                    }
                                },
                                onRunIPTVButtonClick = { iptvRedirectFunc2() },
                                onWebTVButtonClick = { startActivity(Intent(this@MainActivity, WebPlayerActivity::class.java)) },
                                onDebugButtonClick = { currentScreen = "Zone" },
                                onCloudPlayButtonClick = { currentScreen = "CloudHome" },
                                onExitButtonClick = { finishAffinity() }
                            )
                            "Settings" -> SettingsScreen(
                                activity = this@MainActivity,
                                checkForUpdates = { checkForUpdates(true) },
                                onNavigate = { currentScreen = it },
                                isSwitchOnForAutoStartForeground = isSwitchOnForAutoStartForeground,
                                onAutoStartForegroundSwitch = { checked: Boolean ->
                                    if (checked) {
                                        if (checkOverlayPermission()) {
                                            preferenceManager.myPrefs.autoStartOnBootForeground = true
                                            preferenceManager.savePreferences()
                                            isSwitchOnForAutoStartForeground = true
                                        } else {
                                            requestOverlayPermission()
                                        }
                                    } else {
                                        preferenceManager.myPrefs.autoStartOnBootForeground = false
                                        preferenceManager.savePreferences()
                                        isSwitchOnForAutoStartForeground = false
                                    }
                                }
                            )
                            "Debug" -> DebugScreen(this@MainActivity) { currentScreen = it }
                            "Zone" -> ZoneScreen(
                                context = this@MainActivity,
                                onNavigate = { currentScreen = it },
                                isServerRunning = isServerRunning,
                                onServerStartClick = {
                                    runBinary(this@MainActivity, emptyArray(), { onJTVServerRun() }, { outputText = it })
                                }
                            )
                            "Info" -> InfoScreen(this@MainActivity)
                            "Runner" -> RunnerScreen(this@MainActivity)
                            "Login" -> LoginScreen(this@MainActivity)
                        }

                        // Restore Popups
                        RedirectPopup(
                            appIPTV = preferenceManager.myPrefs.iptvAppName,
                            appIPTVpkg = preferenceManager.myPrefs.iptvAppPackageName,
                            isVisible = showRedirectPopup,
                            countdownTime = preferenceManager.myPrefs.iptvLaunchCountdown,
                            context = this@MainActivity,
                            onUserCancel = { showRedirectPopup = false; shouldLaunchIPTV = false },
                            onTimeOut = { showRedirectPopup = false }
                        )

                        LoginPopup(
                            isVisible = showLoginPopup,
                            title = "Login Required",
                            text = "Please log in using WebTV to access the server",
                            confirmButtonText = "Login",
                            dismissButtonText = "Cancel",
                            onConfirm = { showLoginPopupD = true; showLoginPopup = false },
                            onDismiss = { showLoginPopup = false },
                            onSettingsClick = {
                                showLoginPopup = false
                                startActivity(Intent(this@MainActivity, WebPlayerActivity::class.java))
                            }
                        )
                        LoginScreenPop(showLoginPopupD, { showLoginPopupD = false }, this@MainActivity)
                        CustPopup(
                            isVisible = showBinaryUpdatePopup,
                            title = "Binary Update Available",
                            text = "A new version of the binary is available. Update now?",
                            confirmButtonText = "Update",
                            dismissButtonText = "Later",
                            onConfirm = { performBinaryUpdate(); showBinaryUpdatePopup = false },
                            onDismiss = { showBinaryUpdatePopup = false }
                        )

                        CustPopup(
                            isVisible = showAppUpdatePopup,
                            title = "App Update Available",
                            text = "A new version of the app is available. Update now!",
                            confirmButtonText = "Update",
                            dismissButtonText = "Later",
                            onConfirm = { performAppUpdate(); showAppUpdatePopup = true },
                            onDismiss = null
                        )

                        if (downloadProgress != null) {
                            ProgressPopup(downloadProgress!!.fileName, downloadProgress!!.progress)
                        }

                        CustPopup(
                            isVisible = showOverlayPermissionPopup,
                            title = "Request Permission",
                            text = "Draw over other apps permission is required for the app to function properly.",
                            confirmButtonText = "Grant",
                            dismissButtonText = "Dismiss",
                            onConfirm = {
                                showOverlayPermissionPopup = false
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
                                    overlayPermissionLauncher.launch(intent)
                                }
                            },
                            onDismiss = { isSwitchOnForAutoStartForeground = false; showOverlayPermissionPopup = false }
                        )

                        JTVModeSelectorPopup(showOperationDialog, { showOperationDialog = false }, { showOperationDialog = false }, preferenceManager, this@MainActivity)
                    }
                }
            }
            LaunchedEffect(Unit) {
                if (preferenceManager.myPrefs.jtvGoBinaryVersion == null || preferenceManager.myPrefs.enableAutoUpdate) checkForUpdates()
            }
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when (currentScreen) {
                "Settings", "Debug", "Info", "JioHome" -> currentScreen = "CloudHome"
                "CloudMain" -> currentScreen = "CloudHome"
                "Runner", "Login", "Zone" -> currentScreen = "Debug"
                else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        }
    }

    private fun onJTVServerRun() {
        val port = preferenceManager.myPrefs.jtvGoServerPort
        serverStartRequestedAt = System.currentTimeMillis()
        CoroutineScope(Dispatchers.IO).launch {
            checkServerStatus(
                port = port,
                onLoginSuccess = {
                    isServerRunning = true
                    isGlowBox = true
                    if (preferenceManager.myPrefs.autoStartIPTV) triggerIPTVRedirect()
                },
                onLoginFailure = {
                    isGlowBox = false
                    isServerRunning = true
                    if (preferenceManager.myPrefs.loginChk) showLoginPopup = true
                    else if (preferenceManager.myPrefs.autoStartIPTV) triggerIPTVRedirect()
                },
                onServerDown = {
                    CoroutineScope(Dispatchers.Main).launch {
                        isGlowBox = false
                        if (System.currentTimeMillis() - serverStartRequestedAt < 15_000L) {
                            isServerRunning = true
                            outputText = "Server starting..."
                            return@launch
                        }
                        if (preferenceManager.myPrefs.autoStartServer || preferenceManager.myPrefs.startTvAutomatically) {
                            if (!BinaryService.isRunning) runBinary(this@MainActivity, emptyArray(), { onJTVServerRun() }, { outputText = it })
                            else { isServerRunning = true; outputText = "Server is starting..." }
                        } else { isServerRunning = false }
                    }
                }
            )
        }
    }

    private fun triggerIPTVRedirect() {
        countdownJob?.cancel()
        var countdownTime = preferenceManager.myPrefs.iptvLaunchCountdown
        countdownJob = CoroutineScope(Dispatchers.Main).launch {
            showRedirectPopup = (currentScreen != "Zone") && !preferenceManager.myPrefs.iptvAppPackageName.isNullOrEmpty()
            shouldLaunchIPTV = true
            while (countdownTime > 0) { delay(1000); countdownTime-- }
            showRedirectPopup = false
            if (shouldLaunchIPTV) startIPTV2()
        }
    }

    private fun checkForUpdates(forceCheck: Boolean = false) {
        CoroutineScope(Dispatchers.IO).launch {
            val ver = preferenceManager.myPrefs.jtvGoBinaryVersion
            if (preferenceManager.myPrefs.jtvGoBinaryName.isNullOrEmpty() || ver.isNullOrEmpty()) {
                performBinaryUpdate()
                return@launch
            }
            if (!preferenceManager.myPrefs.enableAutoUpdate && !forceCheck) return@launch
            val latest = BinaryUpdater.fetchLatestReleaseInfo()
            if (latest?.version?.compareTo(SemanticVersionNew.parse(ver)) == 1) showBinaryUpdatePopup = true
        }
        CoroutineScope(Dispatchers.IO).launch {
            if (!preferenceManager.myPrefs.enableAutoUpdate && !forceCheck) return@launch
            val latest = ApplicationUpdater.fetchLatestReleaseInfo()
            if (latest?.version?.compareTo(SemanticVersionNew.parse(BuildConfig.VERSION_NAME)) == 1) showAppUpdatePopup = true
        }
    }

    private fun performBinaryUpdate() {
        CoroutineScope(Dispatchers.IO).launch {
            val latest = BinaryUpdater.fetchLatestReleaseInfo() ?: return@launch
            downloadFile(latest.downloadUrl, latest.name) { model ->
                when (model.status) {
                    Status.SUCCESS -> {
                        preferenceManager.myPrefs.jtvGoBinaryVersion = latest.version.toString()
                        preferenceManager.myPrefs.jtvGoBinaryName = latest.name
                        preferenceManager.savePreferences()
                        downloadProgress = null
                        if (preferenceManager.myPrefs.operationMODE == 0) restartApp(this@MainActivity)
                    }
                    Status.FAILED -> downloadProgress = null
                    else -> downloadProgress = DownloadProgress(model.fileName, model.progress)
                }
            }
        }
    }

    private fun downloadFile(url: String, fileName: String, update: (DownloadModelNew) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).build()
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) { update(DownloadModelNew(Status.FAILED, fileName, 0, "HTTP ${resp.code}")); return@use }
                    val file = File(filesDir, fileName)
                    resp.body?.byteStream()?.use { input ->
                        file.outputStream().use { out ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            var total = 0L
                            val len = resp.body?.contentLength() ?: -1L
                            while (input.read(buffer).also { read = it } != -1) {
                                out.write(buffer, 0, read)
                                total += read
                                update(DownloadModelNew(Status.IN_PROGRESS, fileName, if (len > 0) (total * 100 / len).toInt() else -1, ""))
                            }
                        }
                    }
                    update(DownloadModelNew(Status.SUCCESS, fileName, 100, ""))
                }
            } catch (e: Exception) { update(DownloadModelNew(Status.FAILED, fileName, 0, e.message ?: "")) }
        }
    }

    private fun performAppUpdate() {
        CoroutineScope(Dispatchers.IO).launch {
            val latest = ApplicationUpdater.fetchLatestReleaseInfo() ?: return@launch
            ApplicationUpdater.downloadAppUpdate(this@MainActivity, latest.downloadUrl, latest.name) { downloadProgress = it }
        }
    }

    private fun getPublicJTVServerURL(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val port = preferenceManager.myPrefs.jtvGoServerPort
        if (preferenceManager.myPrefs.serveLocal) return "http://localhost:$port/playlist.m3u"
        val net = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.activeNetwork else cm.allNetworks.firstOrNull()
        val caps = cm.getNetworkCapabilities(net)
        if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
            val ip = cm.getLinkProperties(net)?.linkAddresses?.find { it.address is Inet4Address }?.address?.hostAddress
            if (ip != null) return "http://$ip:$port/playlist.m3u"
        }
        return "http://localhost:$port/playlist.m3u"
    }

    private val binaryStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BinaryService.ACTION_BINARY_STOPPED) {
                if (preferenceManager.myPrefs.autoStartServer || preferenceManager.myPrefs.startTvAutomatically) {
                    isServerRunning = false
                    runBinary(this@MainActivity, emptyArray(), { onJTVServerRun() }, { outputText = it })
                } else isServerRunning = false
            }
        }
    }

    private fun startIPTV2() {
        val pkg = preferenceManager.myPrefs.iptvAppPackageName
        if (pkg.isNullOrEmpty() || currentScreen == "Zone") return
        executor.execute {
            when (pkg) {
                "webtv" -> runOnUiThread { startActivity(Intent(this, WebPlayerActivity::class.java)) }
                "tvzone" -> runOnUiThread { currentScreen = "Zone" }
                "sonata" -> startActivity(Intent(this, LandingPage::class.java))
                else -> {
                    val intent = packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) startActivity(intent)
                }
            }
        }
    }

    private fun iptvRedirectFunc2() {
        val pkg = preferenceManager.myPrefs.iptvAppPackageName
        if (pkg.isNullOrEmpty()) { startActivity(Intent(this, AppListActivity::class.java)); return }
        when (pkg) {
            "webtv" -> startActivity(Intent(this, WebPlayerActivity::class.java))
            "tvzone" -> currentScreen = "Zone"
            "sonata" -> startActivity(Intent(this, LandingPage::class.java))
            else -> {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) startActivity(intent)
            }
        }
    }

    private fun requestNotificationPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    @RequiresApi(Build.VERSION_CODES.M)
    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        if (Settings.canDrawOverlays(this)) {
            preferenceManager.myPrefs.autoStartOnBootForeground = true
            preferenceManager.savePreferences()
            isSwitchOnForAutoStartForeground = true
        }
    }

    private fun checkOverlayPermission(): Boolean = if (preferenceManager.myPrefs.overlayPermissionAttempts == 3) true else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun requestOverlayPermission() {
        val attempts = preferenceManager.myPrefs.overlayPermissionAttempts
        if (attempts < 1) { showOverlayPermissionPopup = true; preferenceManager.myPrefs.overlayPermissionAttempts = attempts + 1; preferenceManager.savePreferences(); return }
        if (attempts == 2) { grantPermissionAndSave(); preferenceManager.myPrefs.overlayPermissionAttempts = attempts + 1; preferenceManager.savePreferences(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) grantPermissionAndSave()
    }

    private fun grantPermissionAndSave() {
        preferenceManager.myPrefs.autoStartOnBootForeground = true
        preferenceManager.savePreferences()
        isSwitchOnForAutoStartForeground = true
    }
}
