package com.homebase.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.homebase.tv.AppContainer
import com.homebase.tv.ui.theme.Charcoal

/** [rowIds] are the backend rows a media tab shows, in order. */
enum class Destination(val title: String, val rowIds: List<String> = emptyList()) {
    Home("Home", listOf("playlists", "continue_watching", "nuvio_continue")),
    Movies("Movies", listOf("recent_movies", "trending_movies", "trakt_watchlist_movies", "favorite_movies")),
    Shows("Shows", listOf("recent_episodes", "trending_series", "trakt_watchlist_shows", "favorite_shows")),
    Apps("Apps"),
    Settings("Settings"),
}

/** The tab Back moves focus to; on the Home tab it's Home itself, so Back never leaves Homebase. */
fun backTarget(current: Destination, contentHasFocus: Boolean): Destination =
    if (contentHasFocus) current else Destination.Home

private val Backdrop = Brush.linearGradient(listOf(Color(0xFF242A33), Charcoal), start = Offset.Zero, end = Offset(900f, 1400f))

@Composable
fun HomebaseRoot(container: AppContainer) {
    val launcher: LauncherViewModel = viewModel { LauncherViewModel(container.apps, container.settings, container.backend) }
    val settingsVm: SettingsViewModel = viewModel { SettingsViewModel(container.settings, container.backend) }
    val state by launcher.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        launcher.refresh()
        onPauseOrDispose {}
    }

    var destination by rememberSaveable { mutableStateOf(Destination.Home) }
    var contentHasFocus by remember { mutableStateOf(false) }
    var tabsHaveFocus by remember { mutableStateOf(false) }
    var homeRestored by remember { mutableStateOf(false) }
    var focusMemory by rememberSaveable(stateSaver = FocusMemorySaver) { mutableStateOf<FocusMemory?>(null) }
    val tabFocus = remember { Destination.entries.associateWith { FocusRequester() } }
    val allRows = state.home.home?.rows.orEmpty()
    val rows = remember(allRows, destination) { tabRows(allRows, destination.rowIds) }

    // Back: content -> current tab -> Home tab, and stops there like a launcher; letting the system finish
    // the activity would resume whichever app is underneath. The Home button still leaves as usual.
    BackHandler { tabFocus.getValue(backTarget(destination, contentHasFocus)).requestFocus() }

    Box(Modifier.fillMaxSize().background(Backdrop)) {
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 20.dp)) {
            TabRow(
                selectedTabIndex = destination.ordinal,
                modifier = Modifier.focusRestorer().onFocusChanged { tabsHaveFocus = it.hasFocus },
            ) {
                Destination.entries.forEach { d ->
                    Tab(
                        selected = d == destination,
                        onFocus = { destination = d },
                        modifier = Modifier.focusRequester(tabFocus.getValue(d)),
                    ) {
                        Text(d.title, Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { contentHasFocus = it.hasFocus }
                    .focusGroup(),
            ) {
                when (destination) {
                    // key: each tab gets its own scroll and row state.
                    Destination.Home, Destination.Movies, Destination.Shows -> key(destination) { MediaScreen(
                        state = state,
                        rows = rows,
                        showApps = destination == Destination.Home,
                        focusMemory = { focusMemory },
                        tabsHaveFocus = { tabsHaveFocus },
                        restoredOnce = { homeRestored },
                        onRestored = { homeRestored = true },
                        onFocusMemory = { focusMemory = it },
                        onLaunch = launcher::launch,
                        onReorderFavorites = launcher::reorderFavorites,
                        onOpenMedia = launcher::open,
                    ) }
                    Destination.Apps -> AppsScreen(state, launcher::launch, launcher::toggleFavorite)
                    Destination.Settings -> SettingsScreen(settingsVm)
                }
            }
        }
    }
}
