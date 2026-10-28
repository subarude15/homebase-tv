package com.homebase.tv.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

enum class TvName(val label: String) { LivingRoom("Living Room"), Bar("Bar"), Office("Office") }

data class Settings(
    val backendUrl: String,
    val apiToken: String,
    val tvName: TvName,
    val favorites: List<String>,
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            backendUrl = p[BACKEND_URL] ?: DEFAULT_BACKEND_URL,
            apiToken = p[API_TOKEN].orEmpty(),
            tvName = TvName.entries.firstOrNull { it.name == p[TV_NAME] } ?: TvName.LivingRoom,
            favorites = favoritesOf(p[FAVORITES]),
        )
    }

    suspend fun saveConnection(backendUrl: String, apiToken: String, tvName: TvName) {
        store.edit {
            it[BACKEND_URL] = backendUrl.trim()
            it[API_TOKEN] = apiToken.trim()
            it[TV_NAME] = tvName.name
        }
    }

    suspend fun toggleFavorite(packageName: String) {
        store.edit { p -> p[FAVORITES] = toggled(favoritesOf(p[FAVORITES]), packageName).joinToString("\n") }
    }

    suspend fun reorderFavorites(shown: List<String>) {
        store.edit { p -> p[FAVORITES] = reordered(favoritesOf(p[FAVORITES]), shown).joinToString("\n") }
    }

    companion object {
        const val DEFAULT_BACKEND_URL = "http://192.168.1.2:8770"
        private val BACKEND_URL = stringPreferencesKey("backend_url")
        private val API_TOKEN = stringPreferencesKey("api_token")
        private val TV_NAME = stringPreferencesKey("tv_name")
        private val FAVORITES = stringPreferencesKey("favorites")
    }
}

/** The saved favorites, one package per line; a repeated package keeps its first place. */
fun favoritesOf(saved: String?): List<String> = saved?.split('\n')?.filter { it.isNotBlank() }?.distinct().orEmpty()

fun toggled(list: List<String>, item: String): List<String> = if (item in list) list - item else list + item

/**
 * Saves the order Home showed: [shown] fills the places the shown favorites hold, so an uninstalled
 * favorite keeps its place for when it's reinstalled. With no installed favorites, Home shows every
 * app, and reordering them makes them the favorites.
 */
fun reordered(saved: List<String>, shown: List<String>): List<String> {
    if (!saved.containsAll(shown)) return saved - shown.toSet() + shown
    val next = shown.iterator()
    return saved.map { if (it in shown) next.next() else it }
}
