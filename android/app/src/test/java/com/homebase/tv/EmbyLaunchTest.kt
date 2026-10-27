package com.homebase.tv

import com.homebase.tv.data.emby.embyItemUriString
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbyLaunchTest {
    @Test
    fun itemUriTargetsEmbyAndroidTvPlayPath() {
        assertEquals("embyatv://tv.emby.embyatv/play/ep1", embyItemUriString("ep1"))
    }
}
