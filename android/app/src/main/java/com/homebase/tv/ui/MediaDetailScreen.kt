package com.homebase.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.homebase.tv.data.backend.MediaItem
import com.homebase.tv.ui.theme.Accent

@Composable
fun MediaDetailScreen(
    item: MediaItem,
    backendUrl: String,
    onPlay: () -> Unit,
    onClose: () -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(item.id) { playFocus.requestFocus() }

    val base = backendUrl.trim().trimEnd('/')
    val art = item.backdrop ?: item.image
    val playLabel = if (item.progress != null && item.progress > 0f) "Resume" else "Play"
    val meta = listOfNotNull(
        item.subtitle.takeIf { it.isNotBlank() },
        item.year?.toString(),
        item.runtimeMinutes?.let { "$it min" },
    ).distinct().joinToString(" · ")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (art != null) {
            AsyncImage(
                model = base + art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.92f),
                    0.55f to Color.Black.copy(alpha = 0.55f),
                    1f to Color.Black.copy(alpha = 0.25f),
                ),
            ),
        )
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .widthIn(max = 720.dp)
                .padding(horizontal = 64.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.headlineLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(meta, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.progress?.let { p ->
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.width(280.dp).height(6.dp).background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Accent))
                }
            }
            if (item.overview.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    item.overview,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onPlay, modifier = Modifier.focusRequester(playFocus)) {
                    Text(playLabel)
                }
                Button(onClick = onClose) { Text("Back") }
            }
        }
    }
}
