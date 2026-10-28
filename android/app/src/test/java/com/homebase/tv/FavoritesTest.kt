package com.homebase.tv

import com.homebase.tv.data.apps.favoriteRow
import com.homebase.tv.data.settings.favoritesOf
import com.homebase.tv.data.settings.reordered
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
    fun toggleAddsToEndAndRemovesWithoutReshuffling() {
        assertEquals(listOf("a", "b"), toggled(listOf("a"), "b"))
        assertEquals(listOf("b"), toggled(listOf("a", "b"), "a"))
        assertEquals(listOf("c", "a", "d"), toggled(listOf("c", "a", "b", "d"), "b"))
    }

    @Test
    fun savedFavoritesDropBlankLinesAndDuplicates() {
        assertEquals(listOf("emby", "youtube"), favoritesOf("emby\n\nyoutube\nemby\n"))
        assertEquals(emptyList<String>(), favoritesOf(null))
    }

    @Test
    fun reorderingKeepsUninstalledFavoritesInTheirPlace() {
        // "gone" is saved but not installed, so Home showed emby, youtube, plex.
        val saved = listOf("emby", "gone", "youtube", "plex")
        assertEquals(listOf("plex", "gone", "emby", "youtube"), reordered(saved, listOf("plex", "emby", "youtube")))
        // Reinstalled later, it reappears where it was.
        assertEquals(listOf("plex", "gone", "emby", "youtube"), favoriteRow(installed + "gone", reordered(saved, listOf("plex", "emby", "youtube"))) { it })
    }

    @Test
    fun reorderingThenFavoritingAddsTheNewAppAtTheEnd() {
        val order = reordered(listOf("emby", "youtube"), listOf("youtube", "emby"))
        assertEquals(listOf("youtube", "emby", "plex"), toggled(order, "plex"))
    }

    @Test
    fun reorderingWhenHomeShowsEveryAppSavesThemAsFavorites() {
        assertEquals(listOf("youtube", "emby", "plex"), reordered(emptyList(), listOf("youtube", "emby", "plex")))
        assertEquals(listOf("gone", "youtube", "emby"), reordered(listOf("gone"), listOf("youtube", "emby")))
    }
}
