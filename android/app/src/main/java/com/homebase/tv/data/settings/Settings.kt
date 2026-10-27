package com.homebase.tv.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

enum class TvName(val label: String) {
    LivingRoom("Living Room"),
    Basement("Basement"),
    Bedroom("Bedroom"),
}

data class Settings(
    val backendUrl: String = "",
    val apiToken: String = "",
    val tvName: TvName = TvName.LivingRoom,
    val favorites: List<String> = emptyList(),
)

fun toggled(favorites: List<String>, packageName: String): List<String> =
    if (packageName in favorites) favorites - packageName else favorites + packageName

private fun decodeFavorites(raw: String?): List<String> =
    raw?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

class SettingsRepository(context: Context) {
    private val store = context.dataStore

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            backendUrl = p[Keys.URL].orEmpty(),
            apiToken = p[Keys.TOKEN].orEmpty(),
            tvName = p[Keys.TV]?.let { runCatching { TvName.valueOf(it) }.getOrNull() } ?: TvName.LivingRoom,
            favorites = decodeFavorites(p[Keys.FAVORITES]),
        )
    }

    suspend fun saveConnection(url: String, token: String, tvName: TvName) {
        store.edit {
            it[Keys.URL] = url.trim()
            it[Keys.TOKEN] = token.trim()
            it[Keys.TV] = tvName.name
        }
    }

    suspend fun toggleFavorite(packageName: String) {
        store.edit { prefs ->
            val current = decodeFavorites(prefs[Keys.FAVORITES])
            prefs[Keys.FAVORITES] = toggled(current, packageName).joinToString("\n")
        }
    }

    private object Keys {
        val URL = stringPreferencesKey("backend_url")
        val TOKEN = stringPreferencesKey("api_token")
        val TV = stringPreferencesKey("tv_name")
        // Newline-joined so favorite order is preserved (stringSet is unordered).
        val FAVORITES = stringPreferencesKey("favorites")
    }
}
