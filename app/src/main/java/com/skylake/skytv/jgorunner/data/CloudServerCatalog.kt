package com.skylake.skytv.jgorunner.data

import android.content.Context
import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer
import java.util.Locale

private const val JIO_SERVER_LIST_URL_ENC = "aHR0cHM6Ly9jbG91ZHBsYXktYXBwLWpzb24ucGFnZXMuZGV2L2NhdC9qaW90disuanNvbg=="
private const val ZEE5_SERVER_LIST_URL_ENC = "aHR0cHM6Ly9jbG91ZHBsYXktYXBwLWpzb24ucGFnZXMuZGV2L2NhdC96ZWU1Lmpzb24="
private const val SONY_SERVER_LIST_URL_ENC = "aHR0cHM6Ly9jbG91ZHBsYXktYXBwLWpzb24ucGFnZXMuZGV2L2NhdC9zb255Lmpzb24="
private const val FANCODE_SERVER_URL_ENC = "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL2RybWxpdmUvZmFuY29kZS1saXZlLWV2ZW50cy9tYWluL2ZhbmNvZGUuanNvbg=="
private const val SERVER_GROUPS_URL = "https://raw.githubusercontent.com/atanuroy22/j/refs/heads/main/server.json"

private fun decodeUrl(encoded: String): String =
    String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))

data class CloudServerEntry(
    val server: CloudServer,
    val category: String,
    val isWebTv: Boolean
)

data class CloudServerCatalog(
    val entries: List<CloudServerEntry>,
    val byCategory: Map<String, List<CloudServerEntry>>,
    val byUrl: Map<String, CloudServerEntry>
)

suspend fun fetchCloudServerCatalog(context: Context, repository: CloudRepository): CloudServerCatalog {
    val preferenceManager = SkySharedPref.getInstance(context)
    val isSubscribed = preferenceManager.myPrefs.cloudSubExpiry > System.currentTimeMillis() &&
        preferenceManager.myPrefs.cloudAccessKeyValid

    val catalogByCategory = linkedMapOf<String, LinkedHashMap<String, CloudServerEntry>>()
    val categoryKeyByLower = mutableMapOf<String, String>()

    fun resolveCategoryKey(rawName: String): String {
        val trimmed = rawName.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        return categoryKeyByLower.getOrPut(lower) { trimmed }
    }

    fun addServers(category: String, servers: List<CloudServer>, isWebTv: Boolean) {
        if (servers.isEmpty()) return
        val key = resolveCategoryKey(category)
        val bucket = catalogByCategory.getOrPut(key) { linkedMapOf() }
        servers.forEach { server ->
            if (server.url.isBlank() || server.name.isBlank()) return@forEach
            if (!bucket.containsKey(server.url)) {
                bucket[server.url] = CloudServerEntry(server, key, isWebTv)
            }
        }
    }

    val freeJio = CloudServer(
        name = "Free Jio",
        url = "http://localhost:${preferenceManager.myPrefs.jtvGoServerPort}/playlist.m3u",
        logo = "https://raw.githubusercontent.com/atanuroy22/jiotv_go_app/develop/pic/jiotv.jpg"
    )

    val tataPlayPlaylistUrl = preferenceManager.myPrefs.tataPlayPlaylistUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "http://localhost:8000/tataplay/playlist.php"

    val tataPlayServer = CloudServer(
        name = "Tata Play",
        url = tataPlayPlaylistUrl,
        logo = com.skylake.skytv.jgorunner.tata.TataConstants.TATA_PLAY_LOGO_URL
    )

    addServers("Tata Play", listOf(tataPlayServer), false)

    if (!isSubscribed) {
        addServers("JioTV+", listOf(freeJio), false)
        val entries = catalogByCategory.values.flatMap { it.values }
        return CloudServerCatalog(entries, entries.groupBy { it.category }, entries.associateBy { it.server.url })
    }

    val jioServers = repository.fetchServers(decodeUrl(JIO_SERVER_LIST_URL_ENC))
    val zee5Servers = selectSdServerWithFallback(repository.fetchServers(decodeUrl(ZEE5_SERVER_LIST_URL_ENC)))
    val sonyServers = repository.fetchServers(decodeUrl(SONY_SERVER_LIST_URL_ENC))

    val fancodeServer = CloudServer(
        name = "Fancode Live",
        url = decodeUrl(FANCODE_SERVER_URL_ENC),
        logo = "https://downloadr2.apkmirror.com/wp-content/uploads/2021/06/26/60d9761924e40.png"
    )

    addServers("JioTV+", jioServers + freeJio, false)
    addServers("Zee5", zee5Servers, false)
    addServers("Sony", sonyServers, false)
    addServers("Sports", listOf(fancodeServer), false)

    val dynamicGroups = repository.fetchServerGroups(SERVER_GROUPS_URL)
    dynamicGroups.forEach { (category, serverEntries) ->
        serverEntries.forEach { entry ->
            addServers(category, listOf(entry.server), entry.isWebTv)
        }
    }

    val entries = catalogByCategory.values.flatMap { it.values }
    return CloudServerCatalog(entries, entries.groupBy { it.category }, entries.associateBy { it.server.url })
}
