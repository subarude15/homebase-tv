package com.homebase.tv

import com.homebase.tv.data.backend.HomeResponse
import com.homebase.tv.data.backend.MediaItem
import com.homebase.tv.data.backend.embyLink
import com.homebase.tv.data.backend.nuvioLink
import com.homebase.tv.data.backend.nuvioVideoId
import com.homebase.tv.ui.cardDetail
import com.homebase.tv.ui.durationText
import com.homebase.tv.ui.featuredDetail
import com.homebase.tv.data.backend.Shape
import com.homebase.tv.data.backend.backendJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Guards the JSON contract with the backend's /api/home response. */
class HomeContractTest {
    private val sample = """
        {"rows":[{"id":"continue_watching","title":"Continue Watching","shape":"landscape","items":[
          {"id":"ep1","type":"Episode","title":"Bake Off","subtitle":"S7:E7 · Dessert Week","overview":"",
           "year":null,"runtime_minutes":60,"progress":0.38,"image":"/api/image/ep1/Primary?tag=abc",
           "backdrop":null,"future_field":1}]}],
         "updated_at":1730000000,"stale":true}
    """.trimIndent()

    @Test
    fun parsesBackendHomeResponse() {
        val home = backendJson.decodeFromString(HomeResponse.serializer(), sample)
        val row = home.rows.single()
        assertEquals(Shape.Landscape, row.shape)
        val item = row.items.single()
        assertEquals(60, item.runtimeMinutes)
        assertEquals(0.38f, item.progress!!, 0.001f)
        assertNull(item.year)
        assertEquals(true, home.stale)
        assertNull(home.serverId)
    }

    @Test
    fun embyLinkPlaysVideosAndOpensEverythingElse() {
        val item = MediaItem(id = "42", type = "Episode", title = "")
        assertEquals("emby://play/srv/42", embyLink("srv", item))
        assertEquals("emby://play/srv/42", embyLink("srv", item.copy(type = "Movie")))
        assertEquals("emby://items/srv/42", embyLink("srv", item.copy(type = "Series")))
        assertEquals("emby://items/srv/42", embyLink("srv", item.copy(type = "Playlist")))
    }

    @Test
    fun nuvioItemsParseAndLink() {
        val json = """{"id":"trakt-8","type":"Episode","source":"nuvio","content_id":"tt100","title":"Below","season":1,"episode":2}"""
        val ep = backendJson.decodeFromString(MediaItem.serializer(), json)
        assertEquals("nuvio", ep.source)
        assertEquals("nuvio://series/tt100", nuvioLink(ep))
        assertEquals("tt100:1:2", nuvioVideoId(ep))
        val movie = MediaItem(id = "m", type = "Movie", source = "nuvio", contentId = "tmdb:155", title = "")
        assertEquals("nuvio://movie/tmdb:155", nuvioLink(movie))
        assertEquals("tmdb:155", nuvioVideoId(movie))
        assertEquals("emby", MediaItem(id = "e", type = "Movie", title = "").source)
    }

    @Test
    fun favoriteChannelParsesAndSaysWhereItGoes() {
        val json = """{"id":"channel-91950","type":"Channel","source":"tivimate","content_id":"91950","title":"Dog TV",
            "subtitle":"Opens TiviMate","image":"/api/channels/logo/91950"}"""
        val channel = backendJson.decodeFromString(MediaItem.serializer(), json)
        assertEquals("tivimate", channel.source)
        assertEquals("Opens TiviMate", cardDetail(channel))
    }

    @Test
    fun cardAndFeaturedText() {
        val ep = MediaItem(
            id = "1", type = "Episode", title = "Bake Off", subtitle = "S7 E7 · Dessert Week",
            season = 7, episode = 7, runtimeMinutes = 56, progress = 0.375f,
        )
        assertEquals("S7 E7 · 35m left", cardDetail(ep))
        assertEquals("S7 E7 · Dessert Week · 56m · 35m left", featuredDetail(ep))
        val movie = MediaItem(id = "2", type = "Movie", title = "Scary Movie", subtitle = "2026", runtimeMinutes = 95)
        assertEquals("2026", cardDetail(movie))
        assertEquals("1h 28m left", cardDetail(movie.copy(progress = 0.07f)))
        assertEquals("2026 · 1h 35m", featuredDetail(movie))
        val playlist = MediaItem(id = "3", type = "Playlist", title = "90s", subtitle = "2079 items", runtimeMinutes = 47301)
        assertEquals("2079 items", featuredDetail(playlist))
        assertEquals("2h", durationText(120))
    }
}
