package com.skylake.skytv.jgorunner.data

import com.skylake.skytv.jgorunner.ui.tvhome.CloudServer
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudServerSelectorsTest {
    @Test
    fun selectSdServerWithFallback_returnsSecondSdEntry() {
        val servers = listOf(
            CloudServer(name = "Zee5 HD 1", url = "https://example.com/hd1.m3u", logo = ""),
            CloudServer(name = "Zee5 SD 1", url = "https://example.com/sd1.m3u", logo = ""),
            CloudServer(name = "Zee5 SD 2", url = "https://example.com/sd2.m3u", logo = "")
        )

        val selected = selectSdServerWithFallback(servers)

        assertEquals(1, selected.size)
        assertEquals("https://example.com/sd2.m3u", selected[0].url)
    }

    @Test
    fun selectSdServerWithFallback_returnsFirstSdWhenOnlyOneSd() {
        val servers = listOf(
            CloudServer(name = "Zee5 SD 1", url = "https://example.com/sd1.m3u", logo = ""),
            CloudServer(name = "Zee5 HD 1", url = "https://example.com/hd1.m3u", logo = "")
        )

        val selected = selectSdServerWithFallback(servers)

        assertEquals(1, selected.size)
        assertEquals("https://example.com/sd1.m3u", selected[0].url)
    }

    @Test
    fun selectSdServerWithFallback_returnsAllWhenNoSd() {
        val servers = listOf(
            CloudServer(name = "Zee5 HD 1", url = "https://example.com/hd1.m3u", logo = ""),
            CloudServer(name = "Zee5 HD 2", url = "https://example.com/hd2.m3u", logo = "")
        )

        val selected = selectSdServerWithFallback(servers)

        assertEquals(servers, selected)
    }
}
