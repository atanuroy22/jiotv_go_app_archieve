package com.skylake.skytv.jgorunner.data

import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudServerSelectorsTest {
    @Test
    fun selectSecondSdServer_returnsSecondSdEntry() {
        val servers = listOf(
            CloudServer(name = "Zee5 HD 1", url = "https://example.com/hd1.m3u", logo = ""),
            CloudServer(name = "Zee5 SD 1", url = "https://example.com/sd1.m3u", logo = ""),
            CloudServer(name = "Zee5 SD 2", url = "https://example.com/sd2.m3u", logo = "")
        )

        val selected = selectSecondSdServer(servers)

        assertEquals(1, selected.size)
        assertEquals("https://example.com/sd2.m3u", selected[0].url)
    }

    @Test
    fun selectSecondSdServer_returnsEmptyWhenOnlyOneSd() {
        val servers = listOf(
            CloudServer(name = "Zee5 SD 1", url = "https://example.com/sd1.m3u", logo = ""),
            CloudServer(name = "Zee5 HD 1", url = "https://example.com/hd1.m3u", logo = "")
        )

        val selected = selectSecondSdServer(servers)

        assertEquals(0, selected.size)
    }

    @Test
    fun selectSecondSdServer_returnsEmptyWhenNoSd() {
        val servers = listOf(
            CloudServer(name = "Zee5 HD 1", url = "https://example.com/hd1.m3u", logo = ""),
            CloudServer(name = "Zee5 HD 2", url = "https://example.com/hd2.m3u", logo = "")
        )

        val selected = selectSecondSdServer(servers)

        assertEquals(0, selected.size)
    }
}
