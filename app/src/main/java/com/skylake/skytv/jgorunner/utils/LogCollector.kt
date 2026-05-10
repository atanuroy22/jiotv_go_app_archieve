package com.skylake.skytv.jgorunner.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.util.LinkedList

object LogCollector {
    private val logs = LinkedList<String>()
    private const val MAX_LOGS = 100

    fun log(message: String) {
        synchronized(logs) {
            logs.addFirst("${System.currentTimeMillis()}: $message")
            if (logs.size > MAX_LOGS) {
                logs.removeLast()
            }
        }
    }

    fun getLogs(): String {
        return synchronized(logs) {
            logs.joinToString("\n")
        }
    }

    fun copyToClipboard(context: Context) {
        val logText = getLogs()
        if (logText.isEmpty()) {
            Toast.makeText(context, "No logs to copy", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SkyTV Logs", logText)
            clipboard.setPrimaryClip(clip)

            // For Android 12 and below, we show a toast. Android 13+ has its own UI.
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
                Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to copy: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
