package com.homebase.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.homebase.tv.data.backend.MediaItem
import com.homebase.tv.data.backend.MediaRow

// Room for the focused card's scale-up so its border isn't clipped.
private val CardPadding = PaddingValues(vertical = 10.dp, horizontal = 12.dp)

const val APPS_ROW = "apps"

/** The rows a tab shows, in the tab's order whatever order the backend sends; missing or empty rows are skipped. */
fun tabRows(rows: List<MediaRow>, ids: List<String>): List<MediaRow> {
    val byId = rows.associateBy { it.id }
    return ids.mapNotNull { id -> byId[id]?.takeIf { it.items.isNotEmpty() } }
}

/** The last focused card, so Home can put focus back after Emby (even if Android recreated the activity). */
data class FocusMemory(val rowId: String, val key: String, val index: Int)

/** Whether two memories name the same card; the index alone shifts when a row reorders. */
fun sameCard(a: FocusMemory?, b: FocusMemory?): Boolean = a?.rowId == b?.rowId && a?.key == b?.key

/** Where [m]'s card is in a row of [keys]; if it left the row (e.g. an episode finished), the same position. */
fun restoreIndex(keys: List<String>, m: FocusMemory): Int = keys.indexOf(m.key).takeIf { it >= 0 } ?: m.index.coerceIn(0, keys.lastIndex)

/** A remote key as Move Mode sees it; [repeat] counts the key-repeats Android sends while it's held. */
data class RemoteKey(val code: Code, val down: Boolean, val repeat: Int = 0) {
    enum class Code { Left, Right, Up, Down, Ok }
}

/**
 * Moving the Favorite App [pkg] within [order]. [armed] is set by an OK press made in Move Mode, so the
 * release of the long press that started it doesn't confirm straight away.
 */
data class MoveMode(val pkg: String, val order: List<String>, val armed: Boolean = false)

/**
 * The next Move Mode (null once OK confirms [MoveMode.order]) and whether the card must not see [key].
 * The long press's own repeats and release go through: the card clears its long-click flag on that
 * release, and would otherwise swallow the next OK instead of launching.
 */
fun MoveMode.on(key: RemoteKey): Pair<MoveMode?, Boolean> = when (key.code) {
    RemoteKey.Code.Ok -> when {
        key.down && key.repeat == 0 -> copy(armed = true) to true
        key.down -> this to armed
        armed -> null to true
        else -> this to false
    }
    RemoteKey.Code.Left -> (if (key.down) copy(order = movedBy(order, pkg, -1)) else this) to true
    RemoteKey.Code.Right -> (if (key.down) copy(order = movedBy(order, pkg, 1)) else this) to true
    // Up and Down would take focus off the app being moved.
    RemoteKey.Code.Up, RemoteKey.Code.Down -> this to true
}

/** [order] with [item] moved [by] places, or unchanged at either end. */
fun movedBy(order: List<String>, item: String, by: Int): List<String> {
    val from = order.indexOf(item)
    val to = from + by
    if (from < 0 || to !in order.indices) return order
    return order.toMutableList().apply { add(to, removeAt(from)) }
}

private fun remoteKey(e: KeyEvent): RemoteKey? {
    val code = when (e.key) {
        Key.DirectionLeft -> RemoteKey.Code.Left
        Key.DirectionRight -> RemoteKey.Code.Right
        Key.DirectionUp -> RemoteKey.Code.Up
        Key.DirectionDown -> RemoteKey.Code.Down
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> RemoteKey.Code.Ok
        else -> return null
    }
    return RemoteKey(code, e.type == KeyEventType.KeyDown, e.nativeKeyEvent.repeatCount)
}

val FocusMemorySaver = Saver<FocusMemory?, String>(
    save = { it?.let { m -> "${m.rowId}\n${m.key}\n${m.index}" } },
    restore = { s -> s.split('\n').let { FocusMemory(it[0], it[1], it[2].toInt()) } },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaScreen(
    state: LauncherState,
    rows: List<MediaRow>,
    showApps: Boolean,
    // Read live: a restore must see a focus move made while it is still running.
    focusMemory: () -> FocusMemory?,
    tabsHaveFocus: () -> Boolean,
    restoredOnce: () -> Boolean,
    onRestored: () -> Unit,
    onFocusMemory: (FocusMemory) -> Unit,
    onLaunch: (String) -> Unit,
    onReorderFavorites: (List<String>) -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
) {
    val status = homeStatus(state)
    val firstRowIndex = (if (showApps) 1 else 0) + (if (status != null) 1 else 0)
    val listState = rememberLazyListState()
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    val requesters = remember { mutableMapOf<String, CardHandle>() }
    // App icons are rebuilt on every resume, so the restore keys on package names rather than the apps.
    val appKeys = state.favoriteApps.map { it.packageName }

    var move by remember { mutableStateOf<MoveMode?>(null) }
    // A confirmed order, shown until the saved favorites catch up so the row doesn't flick back.
    var confirmed by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(appKeys) { confirmed = null }
    val shownKeys = move?.order ?: confirmed ?: appKeys
    val apps = state.favoriteApps.associateBy { it.packageName }.let { byPackage -> shownKeys.mapNotNull { byPackage[it] } }
    BackHandler(enabled = move != null) { move = null }
    LaunchedEffect(move?.order) {
        val m = move ?: return@LaunchedEffect
        withFrameNanos {}
        requesters["$APPS_ROW/${m.pkg}"]?.view?.bringIntoView()
    }

    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { move = null }
    }

    // Waits for window focus: until then, Android hands focus to the first tab and would undo the restore.
    val windowFocused = LocalWindowInfo.current.isWindowFocused

    // Re-run on resume and whenever rows reload; a no-op when the remembered card already has focus.
    LaunchedEffect(resumes, rows, appKeys, windowFocused) {
        if (!windowFocused || move != null) return@LaunchedEffect
        val start = focusMemory()
        val m = start ?: FocusMemory(APPS_ROW, appKeys.firstOrNull() ?: return@LaunchedEffect, 0)
        if (m.rowId == APPS_ROW && !showApps) return@LaunchedEffect
        // A new activity hands initial focus to the tabs, so its first restore ignores where focus is.
        if (restoredOnce() && tabsHaveFocus()) return@LaunchedEffect
        val (lazyIndex, keys) = if (m.rowId == APPS_ROW) {
            0 to shownKeys
        } else {
            val r = rows.indexOfFirst { it.id == m.rowId }.takeIf { it >= 0 } ?: return@LaunchedEffect
            firstRowIndex + r to rows[r].items.map { it.id }
        }
        if (keys.isEmpty()) return@LaunchedEffect
        val index = restoreIndex(keys, m)
        // Gives up as soon as the user moves to another card, or OK would land on the card it pulled focus back to.
        val stillWanted = { sameCard(focusMemory(), start) }
        if (restoreFocus(listState, lazyIndex, { rowStates[m.rowId] }, index, stillWanted) { requesters["${m.rowId}/${keys[index]}"] }) {
            onRestored()
        }
    }

    val rowScrolling = LocalBringIntoViewSpec.current
    val lead = with(LocalDensity.current) { 40.dp.toPx() }
    val columnScrolling = remember(lead) { pinToTop(lead) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides columnScrolling) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (showApps) item(key = APPS_ROW) {
                when {
                    state.loading -> Text("Loading apps…")
                    state.apps.isEmpty() -> Text("No TV apps found")
                    else -> CardRow(rowStates.getOrPut(APPS_ROW) { LazyListState() }, rowScrolling) {
                        itemsIndexed(apps, key = { _, app -> app.packageName }) { i, app ->
                            AppCard(
                                app = app,
                                onClick = { onLaunch(app.packageName) },
                                onLongClick = { move = MoveMode(app.packageName, shownKeys) },
                                moving = move?.pkg == app.packageName,
                                width = 132.dp,
                                modifier = Modifier
                                    .remembered(requesters, "$APPS_ROW/${app.packageName}")
                                    .onFocusChanged { if (it.hasFocus) onFocusMemory(FocusMemory(APPS_ROW, app.packageName, i)) }
                                    // Ahead of the card's own click handling, which only sees what this lets through.
                                    .onPreviewKeyEvent { e ->
                                        val m = move?.takeIf { it.pkg == app.packageName } ?: return@onPreviewKeyEvent false
                                        val key = remoteKey(e) ?: return@onPreviewKeyEvent false
                                        val (next, consumed) = m.on(key)
                                        if (next == null) {
                                            confirmed = m.order
                                            onReorderFavorites(m.order)
                                        }
                                        move = next
                                        consumed
                                    },
                            )
                        }
                    }
                }
            }
            status?.let {
                item(key = "status") {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            rows.forEach { row ->
                item(key = row.id) {
                    Column {
                        Text(row.title, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                        CardRow(rowStates.getOrPut(row.id) { LazyListState() }, rowScrolling) {
                            itemsIndexed(row.items, key = { _, item -> item.id }) { i, item ->
                                MediaCard(
                                    item,
                                    row.shape,
                                    state.backendUrl,
                                    onClick = { onOpenMedia(item) },
                                    modifier = Modifier
                                        .remembered(requesters, "${row.id}/${item.id}")
                                        .onFocusChanged { if (it.hasFocus) onFocusMemory(FocusMemory(row.id, item.id, i)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Scrolls the column so the focused row sits at the top, like Google TV. The request is for the card,
 * so [lead] leaves room above it for the row title and the card's focus scale-up.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun pinToTop(lead: Float): BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - lead
}

/** Restores the default horizontal scrolling that [pinToTop] would otherwise override for cards in a row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardRow(state: LazyListState, scrolling: BringIntoViewSpec, content: LazyListScope.() -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides scrolling) {
        LazyRow(
            Modifier.focusRestorer(),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = CardPadding,
            content = content,
        )
    }
}

private class CardHandle(val focus: FocusRequester = FocusRequester(), val view: BringIntoViewRequester = BringIntoViewRequester())

/** Registers a [CardHandle] for this card under [key] while it's composed. */
@Composable
private fun Modifier.remembered(requesters: MutableMap<String, CardHandle>, key: String): Modifier {
    val handle = remember { CardHandle() }
    DisposableEffect(key) {
        requesters[key] = handle
        onDispose { if (requesters[key] === handle) requesters.remove(key) }
    }
    return focusRequester(handle.focus).bringIntoViewRequester(handle.view)
}

/**
 * Composes the card if needed, focuses it, then brings it into view: when the row reorders around an
 * already-focused card, requestFocus alone doesn't scroll.
 */
private suspend fun restoreFocus(
    listState: LazyListState,
    lazyIndex: Int,
    rowState: () -> LazyListState?,
    index: Int,
    stillWanted: () -> Boolean,
    requester: () -> CardHandle?,
): Boolean {
    if (listState.layoutInfo.visibleItemsInfo.none { it.index == lazyIndex }) listState.scrollToItem(lazyIndex)
    repeat(10) {
        withFrameNanos {}
        if (!stillWanted()) return false
        val row = rowState() ?: return@repeat
        if (row.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            row.scrollToItem(index)
            return@repeat
        }
        val target = requester() ?: return@repeat
        if (runCatching { target.focus.requestFocus() }.isSuccess) {
            target.view.bringIntoView()
            return true
        }
    }
    return false
}

private fun homeStatus(state: LauncherState): String? {
    val (home, error) = state.home
    return when {
        home == null && state.homeLoading -> "Loading your library…"
        home == null && error != null -> "Can't reach the Homebase server: $error"
        error != null -> "Can't reach the Homebase server — showing saved content"
        home?.stale == true -> "A media source is unreachable — some rows may be out of date"
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
