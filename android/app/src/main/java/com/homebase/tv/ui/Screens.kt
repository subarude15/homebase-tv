package com.homebase.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

// Room for the focused card's scale-up so its border isn't clipped.
private val CardPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp)

@Composable
fun HomeScreen(state: LauncherState, onLaunch: (String) -> Unit, firstItemFocus: FocusRequester) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Column {
                Text("Favorite Apps", style = MaterialTheme.typography.titleMedium)
                when {
                    state.loading -> Text("Loading apps…")
                    state.apps.isEmpty() -> Text("No TV apps found")
                    else -> LazyRow(
                        Modifier.focusRestorer(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = CardPadding,
                    ) {
                        itemsIndexed(state.favoriteApps, key = { _, app -> app.packageName }) { i, app ->
                            AppCard(
                                app = app,
                                onClick = { onLaunch(app.packageName) },
                                modifier = if (i == 0) Modifier.focusRequester(firstItemFocus) else Modifier,
                            )
                        }
                    }
                }
            }
        }
        homeStatus(state)?.let { status ->
            item {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(state.home.home?.rows.orEmpty(), key = { it.id }) { row ->
            Column {
                Text(row.title, style = MaterialTheme.typography.titleMedium)
                LazyRow(
                    Modifier.focusRestorer(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = CardPadding,
                ) {
                    items(row.items, key = { it.id }) { item ->
                        // Phase 1C wires up play/resume and detail pages.
                        MediaCard(item, row.shape, state.backendUrl, onClick = {})
                    }
                }
            }
        }
    }
}

private fun homeStatus(state: LauncherState): String? {
    val (home, error) = state.home
    return when {
        home == null && state.homeLoading -> "Loading your Emby library…"
        home == null && error != null -> "Can't reach the Homebase server: $error"
        error != null -> "Can't reach the Homebase server — showing saved content"
        home?.stale == true -> "Emby is unreachable — showing saved content"
        else -> null
    }
}

@Composable
fun AppsScreen(state: LauncherState, onLaunch: (String) -> Unit, onToggleFavorite: (String) -> Unit) {
    Column {
        Text(
            "Hold OK on an app to add or remove it from Favorite Apps",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = CardPadding,
        ) {
            gridItems(state.apps, key = { it.packageName }) { app ->
                AppCard(
                    app = app,
                    onClick = { onLaunch(app.packageName) },
                    onLongClick = { onToggleFavorite(app.packageName) },
                    isFavorite = app.packageName in state.favorites,
                )
            }
        }
    }
}

@Composable
fun ComingSoon(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$title is coming in a later phase", style = MaterialTheme.typography.titleMedium)
    }
}
