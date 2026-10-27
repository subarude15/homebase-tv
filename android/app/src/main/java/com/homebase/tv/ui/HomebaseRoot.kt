package com.homebase.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

enum class Destination(val title: String) {
    Home("Home"), Movies("Movies"), Shows("Shows"), LiveTv("Live TV"), Sports("Sports"), Apps("Apps"), Settings("Settings")
}

@Composable
fun HomebaseRoot(container: AppContainer) {
    val launcher: LauncherViewModel = viewModel { LauncherViewModel(container.apps, container.settings) }
    val settingsVm: SettingsViewModel = viewModel { SettingsViewModel(container.settings, container.backend) }
    val state by launcher.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        launcher.refresh()
        onPauseOrDispose {}
    }

    var destination by rememberSaveable { mutableStateOf(Destination.Home) }
    var contentHasFocus by remember { mutableStateOf(false) }
    var didInitialFocus by rememberSaveable { mutableStateOf(false) }
    val tabFocus = remember { Destination.entries.associateWith { FocusRequester() } }
    val firstAppFocus = remember { FocusRequester() }

    // Back: content -> current tab -> Home tab -> exit (system default).
    BackHandler(enabled = contentHasFocus || destination != Destination.Home) {
        val target = if (contentHasFocus) destination else Destination.Home
        tabFocus.getValue(target).requestFocus()
    }

    LaunchedEffect(state.apps.isNotEmpty()) {
        if (state.apps.isNotEmpty() && !didInitialFocus && destination == Destination.Home) {
            didInitialFocus = runCatching { firstAppFocus.requestFocus() }.isSuccess
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        TabRow(selectedTabIndex = destination.ordinal, modifier = Modifier.focusRestorer()) {
            Destination.entries.forEach { d ->
                Tab(
                    selected = d == destination,
                    onFocus = { destination = d },
                    modifier = Modifier.focusRequester(tabFocus.getValue(d)),
                ) {
                    Text(d.title, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .fillMaxSize()
                .onFocusChanged { contentHasFocus = it.hasFocus }
                .focusGroup(),
        ) {
            when (destination) {
                Destination.Home -> HomeScreen(state, launcher::launch, firstAppFocus)
                Destination.Movies, Destination.Shows, Destination.LiveTv, Destination.Sports -> ComingSoon(destination.title)
                Destination.Apps -> AppsScreen(state, launcher::launch, launcher::toggleFavorite)
                Destination.Settings -> SettingsScreen(settingsVm)
            }
        }
    }
}
