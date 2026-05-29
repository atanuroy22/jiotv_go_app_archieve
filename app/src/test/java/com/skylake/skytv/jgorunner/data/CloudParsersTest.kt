package com.skylake.skytv.jgorunner.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudParsersTest {
    private val gson = Gson()

    @Test
    fun parseServerList_handlesDirectArray() {
        val json = """
            [
              {"name":"A","url":"https://example.com/a.json","logo":"https://example.com/a.png"},
              {"name":"B","url":"https://example.com/b.json","logo":""}
            ]
        """.trimIndent()

        val servers = CloudParsers.parseServerList(gson, json)
        assertEquals(2, servers.size)
        assertEquals("A", servers[0].name)
        assertEquals("https://example.com/a.json", servers[0].url)
    }

    @Test
    fun parseServerList_handlesNestedAndAltKeys() {
        val json = """
            {
              "data": [
                {"serverName":"Zee5 SD 1","endpoint":"https://example.com/zee5.m3u","icon":"https://example.com/zee5.png"}
              ]
            }
        """.trimIndent()

        val servers = CloudParsers.parseServerList(gson, json)
        assertEquals(1, servers.size)
        assertEquals("Zee5 SD 1", servers[0].name)
        assertEquals("https://example.com/zee5.m3u", servers[0].url)
        assertEquals("https://example.com/zee5.png", servers[0].logo)
    }

    @Test
    fun parseM3U_resolvesRelativeUrlsForRemotePlaylists() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="images/logo.png" group-title="Sports",My Channel
            streams/1.m3u8
        """.trimIndent()

        val channels = CloudParsers.parseM3U(
            m3u = m3u,
            playlistUrl = "https://example.com/base/playlist.m3u",
            localBaseServerUrl = null
        )

        assertEquals(1, channels.size)
        assertEquals("hls", channels[0].type)
        assertEquals("My Channel", channels[0].name)
        assertEquals("Sports", channels[0].group)
        assertEquals("https://example.com/base/streams/1.m3u8", channels[0].m3u8Url)
        assertEquals("https://example.com/base/streams/1.m3u8", channels[0].mpdUrl)
        assertEquals("https://example.com/base/images/logo.png", channels[0].logo)
        assertTrue(!channels[0].id.isNullOrBlank())
    }

    @Test
    fun parseM3U_rewritesToLocalPlayEndpointForLocalPlaylists() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="143.png" group-title="General",Local Channel
            http://localhost:5350/live/143.m3u8
        """.trimIndent()

        val channels = CloudParsers.parseM3U(
            m3u = m3u,
            playlistUrl = "http://localhost:5350/playlist.m3u",
            localBaseServerUrl = "http://localhost:5350"
        )

        assertEquals(1, channels.size)
        assertEquals("dash", channels[0].type)
        assertEquals("143", channels[0].id)
        assertEquals("Local Channel", channels[0].name)
        assertEquals("http://localhost:5350/play/143", channels[0].mpdUrl)
        assertEquals("http://localhost:5350/live/143.m3u8", channels[0].m3u8Url)
        assertEquals("http://localhost:5350/jtvimage/143.png", channels[0].logo)
    }

        @Test
        fun parseServerGroups_marksM3uEntriesAsPlayablePlaylists() {
                val json = """
                        {
                            "Star": [
                                {
                                    "name": "",
                                    "url": "",
                                    "image": "",
                                    "m3u": "true"
                                }
                            ]
                        }
                """.trimIndent()

                val groups = CloudParsers.parseServerGroups(gson, json)

                assertEquals(1, groups["Star"]?.size)
                assertFalse(groups["Star"]?.firstOrNull()?.isWebTv ?: true)
                assertEquals("", groups["Star"]?.firstOrNull()?.server?.url)
        }
}
