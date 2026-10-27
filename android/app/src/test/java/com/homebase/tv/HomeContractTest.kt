package com.homebase.tv

import com.homebase.tv.data.backend.HomeResponse
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
    }
}
