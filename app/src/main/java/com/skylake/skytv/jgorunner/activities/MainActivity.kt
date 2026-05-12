package com.skylake.skytv.jgorunner.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.ui.screens.CloudHomeScreen
import com.skylake.skytv.jgorunner.ui.screens.CloudMainScreen
import com.skylake.skytv.jgorunner.ui.tvhome.CloudChannel
import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer
import com.skylake.skytv.jgorunner.ui.tvhome.Main_Layout
import com.skylake.skytv.jgorunner.utils.LogCollector

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

            Scaffold(
                bottomBar = {
                    if (currentScreen != "CloudPlayer") {
                        CloudBottomNavigation(
                            currentScreen = currentScreen,
                            onTabSelected = { currentScreen = it }
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
                        "Settings" -> Box(modifier = Modifier.fillMaxSize()) {
                            Text("Settings Placeholder", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CloudBottomNavigation(
    currentScreen: String,
    onTabSelected: (String) -> Unit
) {
    NavigationBar(
        containerColor = Color(0xFF111111),
        tonalElevation = 8.dp,
        modifier = Modifier.height(60.dp)
    ) {
        val tabs = listOf(
            Triple("Home", "CloudHome", Icons.Default.Home),
            Triple("Jio", "JioHome", Icons.Default.Tv),
            Triple("Settings", "Settings", Icons.Default.Settings)
        )

        tabs.forEach { (label, route, icon) ->
            val isSelected = currentScreen == route || (route == "CloudHome" && currentScreen == "CloudMain")

            var isFocused by remember { mutableStateOf(false) }

            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(route) },
                icon = { Icon(icon, contentDescription = label, tint = if (isSelected || isFocused) Color.Cyan else Color.Gray) },
                label = { Text(label, color = if (isSelected || isFocused) Color.Cyan else Color.Gray, fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color.Cyan.copy(alpha = 0.1f)
                ),
                modifier = Modifier
                    .onFocusChanged { isFocused = it.isFocused }
                    .focusable()
                    .clip(RoundedCornerShape(8.dp))
            )
        }
    }
}
