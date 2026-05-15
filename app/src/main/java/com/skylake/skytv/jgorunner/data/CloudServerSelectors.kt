package com.skylake.skytv.jgorunner.data

import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer

private const val SECOND_SD_SERVER_INDEX = 1

fun selectSecondSdServer(servers: List<CloudServer>): List<CloudServer> {
    val filteredSdServers = servers.filter { it.name.contains("sd", ignoreCase = true) }
    return filteredSdServers.getOrNull(SECOND_SD_SERVER_INDEX)?.let { listOf(it) } ?: emptyList()
}
