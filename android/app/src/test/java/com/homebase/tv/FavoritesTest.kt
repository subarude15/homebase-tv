package com.homebase.tv

import com.homebase.tv.data.apps.favoriteRow
import com.homebase.tv.data.settings.toggled
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritesTest {
    private val installed = listOf("emby", "plex", "youtube")

    @Test
    fun noFavoritesShowsAllApps() {
        assertEquals(installed, favoriteRow(installed, emptyList()) { it })
    }

    @Test
    fun favoritesKeepSavedOrderAndSkipUninstalled() {
        assertEquals(listOf("youtube", "emby"), favoriteRow(installed, listOf("youtube", "gone", "emby")) { it })
    }

    @Test
    fun allFavoritesUninstalledFallsBackToAllApps() {
        assertEquals(installed, favoriteRow(installed, listOf("gone")) { it })
    }

    @Test
    fun toggleAddsToEndAndRemoves() {
        assertEquals(listOf("a", "b"), toggled(listOf("a"), "b"))
        assertEquals(listOf("b"), toggled(listOf("a", "b"), "a"))
    }
}
