package com.skylake.skytv.jgorunner.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.ui.components.BottomNavigationBar
import com.skylake.skytv.jgorunner.ui.screens.CloudHomeScreen
import com.skylake.skytv.jgorunner.ui.screens.CloudMainScreen
import com.skylake.skytv.jgorunner.ui.screens.DebugScreen
import com.skylake.skytv.jgorunner.ui.screens.SettingsScreen
import com.skylake.skytv.jgorunner.ui.tvhome.CloudChannel
import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer
import com.skylake.skytv.jgorunner.ui.tvhome.Main_Layout
import com.skylake.skytv.jgorunner.utils.LogCollector
import com.skylake.skytv.jgorunner.core.update.ApplicationUpdater

class MainActivity : ComponentActivity() {
    private lateinit var preferenceManager: SkySharedPref

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager = SkySharedPref.getInstance(this)

        val targetScreen = intent?.getStringExtra("target_screen") ?: "CloudHome"

        setContent {
            var currentScreen by remember { mutableStateOf(targetScreen) }
            var selectedServer by remember { mutableStateOf<CloudServer?>(null) }

            var isSwitchOnForAutoStartForeground by remember {
                mutableStateOf(preferenceManager.myPrefs.autoStartOnBootForeground)
            }

            Scaffold(
                bottomBar = {
                    if (currentScreen != "CloudPlayer") {
                        BottomNavigationBar(
                            currentScreen = currentScreen,
                            setCurrentScreen = { currentScreen = it }
                        )
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color.Black)) {
                    when (currentScreen) {
                        "CloudHome" -> CloudHomeScreen(
                            context = this@MainActivity,
                            onServerSelected = {
                                selectedServer = it
                                currentScreen = "CloudMain"
                            },
                            onNavigate = { currentScreen = it }
                        )
                        "CloudMain" -> CloudMainScreen(
                            context = this@MainActivity,
                            initialServer = selectedServer,
                            onNavigate = { currentScreen = it },
                            onPlayChannel = { channel, list ->
                                val intent = Intent(this@MainActivity, CloudPlayerActivity::class.java).apply {
                                    putExtra("current_cloud_channel_index", list.indexOf(channel))
                                    putExtra("server_url", selectedServer?.url)
                                }
                                startActivity(intent)
                            }
                        )
                        "JioHome" -> Main_Layout(this@MainActivity, reloadTrigger = 0)
                        "Settings" -> SettingsScreen(
                            activity = this@MainActivity,
                            checkForUpdates = {
                                // Placeholder for update check, original used ViewModel/Repository usually
                            },
                            onNavigate = { currentScreen = it },
                            isSwitchOnForAutoStartForeground = isSwitchOnForAutoStartForeground,
                            onAutoStartForegroundSwitch = {
                                isSwitchOnForAutoStartForeground = it
                                preferenceManager.myPrefs.autoStartOnBootForeground = it
                                preferenceManager.savePreferences()
                            }
                        )
                        "Debug" -> DebugScreen(this@MainActivity) { currentScreen = it }
                    }
                }
            }
        }
    }
}
