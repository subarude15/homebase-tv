package com.homebase.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.tv.data.apps.InstalledApp
import com.homebase.tv.data.apps.InstalledAppsRepository
import com.homebase.tv.data.apps.favoriteRow
import com.homebase.tv.data.backend.BackendRepository
import com.homebase.tv.data.backend.Health
import com.homebase.tv.data.settings.Settings
import com.homebase.tv.data.settings.SettingsRepository
import com.homebase.tv.data.settings.TvName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LauncherState(
    val loading: Boolean = true,
    val apps: List<InstalledApp> = emptyList(),
    val favorites: List<String> = emptyList(),
) {
    val favoriteApps: List<InstalledApp> get() = favoriteRow(apps, favorites) { it.packageName }
}

class LauncherViewModel(
    private val appsRepo: InstalledAppsRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    private val apps = MutableStateFlow<List<InstalledApp>?>(null)

    val state: StateFlow<LauncherState> = combine(apps, settingsRepo.settings) { a, s ->
        LauncherState(loading = a == null, apps = a.orEmpty(), favorites = s.favorites)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherState())

    fun refresh() {
        viewModelScope.launch { apps.value = appsRepo.load() }
    }

    fun launch(packageName: String) = appsRepo.launch(packageName)

    fun toggleFavorite(packageName: String) {
        viewModelScope.launch { settingsRepo.toggleFavorite(packageName) }
    }
}

sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data object Testing : ConnectionStatus
    data class Ok(val health: Health) : ConnectionStatus
    data class Failed(val message: String) : ConnectionStatus
}

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val backend: BackendRepository,
) : ViewModel() {
    val settings: StateFlow<Settings?> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)

    fun saveAndTest(url: String, token: String, tvName: TvName) {
        viewModelScope.launch {
            settingsRepo.saveConnection(url, token, tvName)
            status.value = ConnectionStatus.Testing
            status.value = backend.health(url, token).fold(
                onSuccess = { ConnectionStatus.Ok(it) },
                onFailure = { ConnectionStatus.Failed(it.message ?: it::class.simpleName.orEmpty()) },
            )
        }
    }
}
