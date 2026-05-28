package com.skylake.skytv.jgorunner.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.skylake.skytv.jgorunner.BuildConfig
import com.skylake.skytv.jgorunner.R
import com.skylake.skytv.jgorunner.activities.MainActivity
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.tata.TataBundleManager
import com.skylake.skytv.jgorunner.tata.TataConstants
import com.skylake.skytv.jgorunner.tata.TataHttpServer
import com.skylake.skytv.jgorunner.utils.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TataServerService : Service() {
    companion object {
        private const val CHANNEL_ID = "TataServerChannel"
        private const val NOTIFICATION_ID = 2
        const val ACTION_START: String = "${BuildConfig.APPLICATION_ID}.action.TATA_START"
        const val ACTION_STOP: String = "${BuildConfig.APPLICATION_ID}.action.TATA_STOP"
        const val ACTION_RESTART: String = "${BuildConfig.APPLICATION_ID}.action.TATA_RESTART"

        var instance: TataServerService? = null
            private set

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {
            val intent = Intent(context, TataServerService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TataServerService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }

    private var server: TataHttpServer? = null
    private var updateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RESTART) {
            restartServer(forceUpdate = true)
            return START_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())

        if (server != null) {
            return START_STICKY
        }

        instance = this
        val prefs = SkySharedPref.getInstance(this)
        if (!prefs.myPrefs.tataServerEnabled) {
            LogCollector.log("Tata server disabled by settings")
            stopServer()
            return START_NOT_STICKY
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = TataBundleManager.ensureBundleReady(this@TataServerService, log = LogCollector::log)
                val root = result.rootDir
                val port = prefs.myPrefs.tataServerPort.takeIf { it in 1..65535 } ?: TataConstants.DEFAULT_PORT
                server = TataHttpServer(root, port)
                server?.start()
                LogCollector.log("Tata server started on port $port")
                startUpdateLoop()
            } catch (e: Exception) {
                LogCollector.log("Tata server start failed: ${e.message}")
                stopServer()
            }
        }

        return START_STICKY
    }

    private fun restartServer(forceUpdate: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            stopServer()
            val result = TataBundleManager.ensureBundleReady(this@TataServerService, forceUpdate, LogCollector::log)
            val root = result.rootDir
            val prefs = SkySharedPref.getInstance(this@TataServerService)
            val port = prefs.myPrefs.tataServerPort.takeIf { it in 1..65535 } ?: TataConstants.DEFAULT_PORT
            server = TataHttpServer(root, port)
            server?.start()
            LogCollector.log("Tata server restarted on port $port")
            startUpdateLoop()
        }
    }

    private fun stopServer() {
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.w("TataServerService", "Error stopping server", e)
        } finally {
            updateJob?.cancel()
            updateJob = null
            server = null
            instance = null
            stopForeground(true)
            stopSelf()
            LogCollector.log("Tata server stopped")
        }
    }

    private fun startUpdateLoop() {
        updateJob?.cancel()
        updateJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(TataConstants.UPDATE_CHECK_INTERVAL_MS)
                val prefs = SkySharedPref.getInstance(this@TataServerService)
                if (!prefs.myPrefs.tataAutoUpdate) continue
                try {
                    val result = TataBundleManager.ensureBundleReady(this@TataServerService, log = LogCollector::log)
                    if (result.updated) {
                        LogCollector.log("Tata bundle updated, restarting server")
                        server?.stop()
                        val port = prefs.myPrefs.tataServerPort.takeIf { it in 1..65535 } ?: TataConstants.DEFAULT_PORT
                        server = TataHttpServer(result.rootDir, port)
                        server?.start()
                    }
                } catch (e: Exception) {
                    LogCollector.log("Tata auto-update check failed: ${e.message}")
                }
            }
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, TataServerService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Tata Play Server Running")
                .setContentText("The Tata Play portal is running on localhost")
                .setSmallIcon(R.mipmap.ic_launcher_alias2)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.cancel_24px),
                        "Stop Server",
                        stopPendingIntent
                    ).build()
                )
                .build()
        } else {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Tata Play Server Running")
                .setContentText("The Tata Play portal is running on localhost")
                .setSmallIcon(R.mipmap.ic_launcher_alias2)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .addAction(R.drawable.cancel_24px, "Stop Server", stopPendingIntent)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Tata Server Channel",
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
