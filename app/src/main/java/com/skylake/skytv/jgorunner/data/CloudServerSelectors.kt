package com.skylake.skytv.jgorunner.data

import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer

fun selectSecondSdServer(servers: List<CloudServer>): List<CloudServer> {
    val sdServers = servers.filter { it.name.contains("sd", ignoreCase = true) }
    return sdServers.getOrNull(1)?.let { listOf(it) } ?: emptyList()
}
