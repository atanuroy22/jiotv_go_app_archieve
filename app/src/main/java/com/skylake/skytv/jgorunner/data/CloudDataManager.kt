package com.skylake.skytv.jgorunner.data

import com.skylake.skytv.jgorunner.ui.tvhome.CloudChannel

/**
 * Shared manager to avoid passing large JSON strings via Intent
 */
object CloudDataManager {
    var currentChannelList: List<CloudChannel> = emptyList()
}
