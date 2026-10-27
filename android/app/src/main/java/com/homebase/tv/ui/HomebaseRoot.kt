package com.homebase.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.homebase.tv.data.backend.MediaItem
import kotlinx.coroutines.delay

enum class Destination(val title: String) {
    Home("Home"), Movies("Movies"), Shows("Shows"), LiveTv("Live TV"), Sports("Sports"), Apps("Apps"), Settings("Settings")
}

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

    // Settles on the focused card briefly before swapping artwork, so scrolling a row doesn't churn backdrops.
    var focusedMedia by remember { mutableStateOf<MediaItem?>(null) }
    var featured by remember { mutableStateOf<MediaItem?>(null) }
    LaunchedEffect(focusedMedia) {
        delay(300)
        featured = focusedMedia
    }
    val shownFeature = featured ?: state.home.home?.rows?.firstOrNull()?.items?.firstOrNull()
    val showFeatured = destination == Destination.Home && state.showFeatured

    // Back: content -> current tab -> Home tab -> exit (system default).
    BackHandler(enabled = contentHasFocus || destination != Destination.Home) {
        val target = if (contentHasFocus) destination else Destination.Home
        tabFocus.getValue(target).requestFocus()
    }

    Box(Modifier.fillMaxSize()) {
        if (showFeatured) {
            FeaturedBackdrop(
                shownFeature,
                state.backendUrl,
                Modifier.align(Alignment.TopEnd).fillMaxWidth(0.68f).fillMaxHeight(0.62f),
            )
        }
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
                    Destination.Home -> HomeScreen(
                        state = state,
                        featured = shownFeature,
                        focusMemory = focusMemory,
                        tabsHaveFocus = { tabsHaveFocus },
                        restoredOnce = { homeRestored },
                        onRestored = { homeRestored = true },
                        onFocusMemory = { focusMemory = it },
                        onFeature = { focusedMedia = it },
                        onLaunch = launcher::launch,
                        onOpenMedia = launcher::open,
                    )
                    Destination.Movies, Destination.Shows, Destination.LiveTv, Destination.Sports -> ComingSoon(destination.title)
                    Destination.Apps -> AppsScreen(state, launcher::launch, launcher::toggleFavorite)
                    Destination.Settings -> SettingsScreen(settingsVm)
                }
            }
        }
    }
}
