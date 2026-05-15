package com.skylake.skytv.jgorunner.data

import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer

private const val SECOND_SD_SERVER_INDEX = 1

fun selectSecondSdServer(servers: List<CloudServer>): List<CloudServer> {
    val filteredSdServers = servers.filter { it.name.contains("sd", ignoreCase = true) }
    return when {
        filteredSdServers.size > SECOND_SD_SERVER_INDEX -> listOf(filteredSdServers[SECOND_SD_SERVER_INDEX])
        filteredSdServers.isNotEmpty() -> listOf(filteredSdServers.first())
        else -> servers
    }
}
