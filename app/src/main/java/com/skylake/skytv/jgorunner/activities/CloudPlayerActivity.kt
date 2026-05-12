package com.skylake.skytv.jgorunner.activities

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.skylake.skytv.jgorunner.data.CloudDataManager
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.ui.screens.CloudPlayerScreen
import com.skylake.skytv.jgorunner.ui.theme.JGOTheme

class CloudPlayerActivity : ComponentActivity() {

    private var initialIndexState by mutableIntStateOf(0)
    private var serverUrl: String? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefManager = SkySharedPref.getInstance(this)
        val channels = CloudDataManager.currentChannelList ?: emptyList()
        initialIndexState = intent.getIntExtra("current_cloud_channel_index", 0)
        serverUrl = intent.getStringExtra("server_url")

        applyImmersive(this)

        setContent {
            JGOTheme(themeOverride = prefManager.myPrefs.darkMODE) {
                CloudPlayerScreen(
                    preferenceManager = prefManager,
                    cloudChannelList = channels,
                    initialIndex = initialIndexState,
                    serverUrl = serverUrl
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newIndex = intent.getIntExtra("current_cloud_channel_index", -1)
        if (newIndex != -1) {
            initialIndexState = newIndex
        }
        serverUrl = intent.getStringExtra("server_url")
    }

    private fun applyImmersive(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val windowInsetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
