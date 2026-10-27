package com.homebase.tv

import com.homebase.tv.data.backend.HomeResponse
import com.homebase.tv.data.backend.MediaItem
import com.homebase.tv.data.backend.embyLink
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
