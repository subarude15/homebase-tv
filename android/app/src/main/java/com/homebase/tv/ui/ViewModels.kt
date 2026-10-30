package com.homebase.tv.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebase.tv.data.apps.InstalledApp
import com.homebase.tv.data.apps.InstalledAppsRepository
import com.homebase.tv.data.apps.favoriteRow
import com.homebase.tv.data.backend.BackendRepository
import com.homebase.tv.data.backend.Health
import com.homebase.tv.data.backend.HomeResult
import com.homebase.tv.data.backend.MediaItem
import com.homebase.tv.data.backend.NUVIO_PACKAGE
import com.homebase.tv.data.backend.embyLink
import com.homebase.tv.data.backend.nuvioLink
import com.homebase.tv.data.backend.nuvioStreams
import com.homebase.tv.data.backend.nuvioType
import com.homebase.tv.data.backend.nuvioVideoId
import com.homebase.tv.data.settings.Settings
import com.homebase.tv.data.settings.SettingsRepository
import com.homebase.tv.data.settings.TvName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
private const val TIVIMATE_PACKAGE = "ar.tvplayer.tv"

/**
 * Streamed items ([nuvioStreams]) use the extras Nuvio's own Google TV cards send, which start playback per
 * Nuvio's auto-play setting; everything else opens its detail page.
 */
private fun nuvioIntent(item: MediaItem): Intent =
    if (nuvioStreams(item)) {
        Intent(Intent.ACTION_VIEW)
            .setClassName(NUVIO_PACKAGE, "$NUVIO_PACKAGE.MainActivity")
            .putExtra("launchMode", "stream")
            .putExtra("contentId", item.contentId)
            .putExtra("contentType", item.nuvioType)
            .putExtra("videoId", nuvioVideoId(item))
            .putExtra("name", item.title)
            .apply {
                item.season?.let { putExtra("season", it) }
                item.episode?.let { putExtra("episode", it) }
            }
    } else {
        Intent(Intent.ACTION_VIEW, Uri.parse(nuvioLink(item))).setPackage(NUVIO_PACKAGE)
    }

data class LauncherState(
    val loading: Boolean = true,
    val apps: List<InstalledApp> = emptyList(),
    val favorites: List<String> = emptyList(),
    val backendUrl: String = "",
    val home: HomeResult = HomeResult(null, null),
    val homeLoading: Boolean = true,
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
        LauncherState(a == null, a.orEmpty(), s.favorites, s.backendUrl, h, loading)
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

    /** Falls back to opening the app itself when there's nothing to link to (e.g. Emby's server ID is missing). */
    fun open(item: MediaItem) {
        // TiviMate has no channel deep links, so a favorite channel can only open the app.
        if (item.source == "tivimate") {
            appsRepo.launch(TIVIMATE_PACKAGE)
            return
        }
        if (item.source == "nuvio") {
            if (item.contentId == null || !appsRepo.start(nuvioIntent(item))) appsRepo.launch(NUVIO_PACKAGE)
            return
        }
        val serverId = home.value.home?.serverId
        if (serverId == null || !appsRepo.openLink(embyLink(serverId, item), EMBY_PACKAGE)) appsRepo.launch(EMBY_PACKAGE)
    }

    fun toggleFavorite(packageName: String) {
        viewModelScope.launch { settingsRepo.toggleFavorite(packageName) }
    }

    fun reorderFavorites(shown: List<String>) {
        viewModelScope.launch { settingsRepo.reorderFavorites(shown) }
    }
}

sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data object Testing : ConnectionStatus
    /** [checkedAt] is epoch millis. */
    data class Ok(val health: Health, val checkedAt: Long) : ConnectionStatus
    data class Failed(val message: String, val checkedAt: Long) : ConnectionStatus
}

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val backend: BackendRepository,
) : ViewModel() {
    val settings: StateFlow<Settings?> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)

    private var check: Job? = null

    fun saveAndTest(url: String, token: String, tvName: TvName) {
        viewModelScope.launch {
            settingsRepo.saveConnection(url, token, tvName)
            test(url, token)
        }
    }

    /** Checks the saved connection, ignoring unsaved edits. */
    fun testSaved() {
        viewModelScope.launch { settingsRepo.settings.first().let { test(it.backendUrl, it.apiToken) } }
    }

    /** Only the newest check may report, so a slow earlier one can't overwrite it. */
    private fun test(url: String, token: String) {
        check?.cancel()
        status.value = ConnectionStatus.Testing
        check = viewModelScope.launch {
            val result = backend.health(url, token)
            ensureActive()
            val now = System.currentTimeMillis()
            status.value = result.fold(
                onSuccess = { ConnectionStatus.Ok(it, now) },
                onFailure = { ConnectionStatus.Failed(it.message ?: it::class.simpleName.orEmpty(), now) },
            )
        }
    }
}
