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
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("SkyTV Logs", getLogs())
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
