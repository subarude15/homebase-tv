package com.homebase.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.tv.data.apps.InstalledApp
import com.homebase.tv.data.apps.InstalledAppsRepository
import com.homebase.tv.data.apps.favoriteRow
import com.homebase.tv.data.backend.BackendRepository
import com.homebase.tv.data.backend.Health
import com.homebase.tv.data.backend.HomeResult
import com.homebase.tv.data.backend.MediaItem
import com.homebase.tv.data.backend.embyLink
import com.homebase.tv.data.settings.Settings
import com.homebase.tv.data.settings.SettingsRepository
import com.homebase.tv.data.settings.TvName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val EMBY_PACKAGE = "com.mb.android"

data class LauncherState(
    val loading: Boolean = true,
    val apps: List<InstalledApp> = emptyList(),
    val favorites: List<String> = emptyList(),
    val backendUrl: String = "",
    val home: HomeResult = HomeResult(null, null),
    val homeLoading: Boolean = true,
    val showFeatured: Boolean = true,
) {
    val favoriteApps: List<InstalledApp> get() = favoriteRow(apps, favorites) { it.packageName }
}

class LauncherViewModel(
    private val appsRepo: InstalledAppsRepository,
    private val settingsRepo: SettingsRepository,
    private val backend: BackendRepository,
) : ViewModel() {
    private val apps = MutableStateFlow<List<InstalledApp>?>(null)
    private val home = MutableStateFlow(HomeResult(null, null))
    private val homeLoading = MutableStateFlow(true)

    val state: StateFlow<LauncherState> = combine(apps, settingsRepo.settings, home, homeLoading) { a, s, h, loading ->
        LauncherState(a == null, a.orEmpty(), s.favorites, s.backendUrl, h, loading, s.showFeatured)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherState())

    init {
        viewModelScope.launch {
            settingsRepo.settings.map { it.backendUrl to it.apiToken }.distinctUntilChanged().collectLatest { loadHome() }
        }
    }

    fun refresh() {
        viewModelScope.launch { apps.value = appsRepo.load() }
        viewModelScope.launch { loadHome() }
    }

    private suspend fun loadHome() {
        val s = settingsRepo.settings.first()
        backend.useToken(s.apiToken)
        if (home.value.home == null) {
            home.value = HomeResult(withContext(Dispatchers.IO) { backend.cachedHome() }, null)
        }
        homeLoading.value = true
        home.value = backend.home(s.backendUrl, s.apiToken)
        homeLoading.value = false
    }

    fun launch(packageName: String) = appsRepo.launch(packageName)

    /** Falls back to opening Emby when the backend hasn't sent a server ID (older backend or cache). */
    fun open(item: MediaItem) {
        val serverId = home.value.home?.serverId
        if (serverId == null || !appsRepo.openLink(embyLink(serverId, item), EMBY_PACKAGE)) appsRepo.launch(EMBY_PACKAGE)
    }

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

    fun setShowFeatured(show: Boolean) {
        viewModelScope.launch { settingsRepo.setShowFeatured(show) }
    }

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
