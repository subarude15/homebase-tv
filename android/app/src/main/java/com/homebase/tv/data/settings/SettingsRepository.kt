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
            favorites = p[FAVORITES]?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
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
        store.edit { p ->
            val current = p[FAVORITES]?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
            p[FAVORITES] = toggled(current, packageName).joinToString("\n")
        }
    }

    companion object {
        const val DEFAULT_BACKEND_URL = "http://192.168.1.2:8770"
        private val BACKEND_URL = stringPreferencesKey("backend_url")
        private val API_TOKEN = stringPreferencesKey("api_token")
        private val TV_NAME = stringPreferencesKey("tv_name")
        private val FAVORITES = stringPreferencesKey("favorites")
    }
}

fun toggled(list: List<String>, item: String): List<String> = if (item in list) list - item else list + item
