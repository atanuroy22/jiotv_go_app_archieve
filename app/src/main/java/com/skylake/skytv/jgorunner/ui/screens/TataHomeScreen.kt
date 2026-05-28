package com.skylake.skytv.jgorunner.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import com.skylake.skytv.jgorunner.activities.WebPlayerActivity
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.services.TataServerService
import com.skylake.skytv.jgorunner.ui.components.LoginPopup
import com.skylake.skytv.jgorunner.utils.LogCollector

private const val DEFAULT_TATA_PORTAL_URL = "http://localhost:8000/tataplay/"
private const val DEFAULT_TATA_PLAYLIST_URL = "http://localhost:8000/tataplay/playlist.php"

@Composable
fun TataHomeScreen(
    context: Context,
    onNavigate: (String) -> Unit
) {
    val preferenceManager = SkySharedPref.getInstance(context)
    val scrollState = rememberScrollState()
    var showTataLoginPopup by remember { mutableStateOf(!preferenceManager.myPrefs.tataPlaySetupComplete) }
    var tataServerRunning by remember { mutableStateOf(TataServerService.isRunning) }

    var portalUrl by rememberSaveable {
        mutableStateOf(
            preferenceManager.myPrefs.tataPlayPortalUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_TATA_PORTAL_URL
        )
    }
    var playlistUrl by rememberSaveable {
        mutableStateOf(
            preferenceManager.myPrefs.tataPlayPlaylistUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_TATA_PLAYLIST_URL
        )
    }

    fun normalizeUrl(value: String, fallback: String): String {
        val trimmed = value.trim()
        return if (trimmed.isBlank()) fallback else trimmed
    }

    fun persist() {
        val port = preferenceManager.myPrefs.tataServerPort
        val defaultPortal = "http://localhost:$port/tataplay/"
        val defaultPlaylist = "http://localhost:$port/tataplay/playlist.php"
        val resolvedPortalUrl = normalizeUrl(portalUrl, defaultPortal)
        val resolvedPlaylistUrl = normalizeUrl(playlistUrl, defaultPlaylist)
        preferenceManager.myPrefs.tataPlayPortalUrl = resolvedPortalUrl
        preferenceManager.myPrefs.tataPlayPlaylistUrl = resolvedPlaylistUrl
        preferenceManager.myPrefs.tataPlaySetupComplete = true
        preferenceManager.myPrefs.cloudAutoplayServerUrl = resolvedPlaylistUrl
        preferenceManager.myPrefs.cloudAutoplayFirstChannel = true
        preferenceManager.myPrefs.tataServerEnabled = true
        preferenceManager.savePreferences()
        portalUrl = resolvedPortalUrl
        playlistUrl = resolvedPlaylistUrl
        LogCollector.log("Tata Play setup saved. Portal=$resolvedPortalUrl Playlist=$resolvedPlaylistUrl")
        if (portalUrl.isBlank()) portalUrl = defaultPortal
        if (playlistUrl.isBlank()) playlistUrl = defaultPlaylist
    }

    LaunchedEffect(Unit) {
        if (!preferenceManager.myPrefs.tataPlaySetupComplete) {
            showTataLoginPopup = true
        }
        if (preferenceManager.myPrefs.tataServerEnabled && !TataServerService.isRunning) {
            TataServerService.start(context)
            tataServerRunning = true
            LogCollector.log("Tata server auto-start requested")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1117))
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Tata Play",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Text(
            text = "Point these URLs at your self-hosted TataPlay portal. The playlist entry is added to Cloud automatically.",
            color = Color(0xFFBBC2D4),
            fontSize = 14.sp
        )

        Text(
            text = if (tataServerRunning) "Server status: Running" else "Server status: Stopped",
            color = if (tataServerRunning) Color(0xFF7CFFB3) else Color(0xFFFF8B8B),
            fontSize = 14.sp
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = portalUrl,
                    onValueChange = { portalUrl = it },
                    label = { Text("Portal URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = playlistUrl,
                    onValueChange = { playlistUrl = it },
                    label = { Text("Playlist URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        persist()
                        LogCollector.log("Tata Play portal opened: ${preferenceManager.myPrefs.tataPlayPortalUrl}")
                        context.startActivity(
                            Intent(context, WebPlayerActivity::class.java).apply {
                                putExtra("startup_url", preferenceManager.myPrefs.tataPlayPortalUrl)
                            }
                        )
                    }) {
                        Text("Open Portal")
                    }

                    Button(onClick = {
                        persist()
                        LogCollector.log("Tata Play Cloud opened using playlist: ${preferenceManager.myPrefs.tataPlayPlaylistUrl}")
                        onNavigate("CloudHome")
                    }) {
                        Text("Open Cloud")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        preferenceManager.myPrefs.tataServerEnabled = true
                        preferenceManager.savePreferences()
                        TataServerService.start(context)
                        tataServerRunning = true
                        LogCollector.log("Tata server start requested")
                    }) {
                        Text("Start Server")
                    }
                    Button(onClick = {
                        preferenceManager.myPrefs.tataServerEnabled = false
                        preferenceManager.savePreferences()
                        TataServerService.stop(context)
                        tataServerRunning = false
                        LogCollector.log("Tata server stop requested")
                    }) {
                        Text("Stop Server")
                    }
                    Button(onClick = {
                        context.startService(
                            Intent(context, TataServerService::class.java).apply {
                                action = TataServerService.ACTION_RESTART
                            }
                        )
                        tataServerRunning = true
                        LogCollector.log("Tata server update requested")
                    }) {
                        Text("Update Server")
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Surface(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Saved playlist: ${preferenceManager.myPrefs.tataPlayPlaylistUrl ?: DEFAULT_TATA_PLAYLIST_URL}",
                        modifier = Modifier.padding(12.dp),
                        color = Color(0xFF3D4658)
                    )
                }
            }
        }
    }

    LoginPopup(
        isVisible = showTataLoginPopup,
        title = "Tata Play Login Required",
        text = "Please log in using Tata Play to access the playlist and enable auto setup.",
        confirmButtonText = "Login",
        dismissButtonText = "Later",
        onConfirm = {
            persist()
            showTataLoginPopup = false
            onNavigate("CloudHome")
        },
        onDismiss = {
            showTataLoginPopup = false
        },
        onSettingsClick = {
            persist()
            context.startActivity(
                Intent(context, WebPlayerActivity::class.java).apply {
                    putExtra("startup_url", preferenceManager.myPrefs.tataPlayPortalUrl)
                }
            )
        }
    )
}