package com.homebase.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.homebase.tv.data.backend.MediaItem
import com.homebase.tv.data.backend.Shape
import com.homebase.tv.data.backend.imageUrl
import com.homebase.tv.data.backend.isPlayable
import com.homebase.tv.ui.theme.Accent
import kotlin.math.roundToInt

private fun Shape.size() = when (this) {
    Shape.Landscape -> 236.dp to 16f / 9f
    Shape.Poster -> 124.dp to 2f / 3f
    Shape.Square -> 160.dp to 1f
}

@Composable
fun MediaCard(item: MediaItem, shape: Shape, backendUrl: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (width, ratio) = shape.size()
    Column(Modifier.width(width)) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().aspectRatio(ratio),
            scale = CardDefaults.scale(focusedScale = 1.06f),
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (item.image != null) {
                    AsyncImage(
                        model = imageUrl(backendUrl, item.image),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        item.title,
                        Modifier.align(Alignment.Center).padding(8.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                item.progress?.let { p ->
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(8.dp).fillMaxWidth().height(4.dp)
                            .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f)),
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Accent))
                    }
                }
            }
        }
        Text(
            item.title,
            // Clears the card's focus scale-up so the title isn't covered.
            Modifier.padding(top = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        cardDetail(item).takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun durationText(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

fun remainingText(item: MediaItem): String? {
    val runtime = item.runtimeMinutes ?: return null
    val progress = item.progress ?: return null
    val left = (runtime * (1 - progress)).roundToInt()
    return if (left > 0) "${durationText(left)} left" else null
}

private fun episodeCode(item: MediaItem): String? =
    if (item.season != null && item.episode != null) "S${item.season} E${item.episode}" else null

/** In-progress cards read like Google TV's ("S7 E7 · 35m left"); the episode name moves to the featured area. */
fun cardDetail(item: MediaItem): String =
    if (item.progress == null) item.subtitle
    else listOfNotNull(episodeCode(item), remainingText(item)).joinToString(" · ").ifBlank { item.subtitle }

fun featuredDetail(item: MediaItem): String = listOfNotNull(
    item.subtitle.ifBlank { null },
    item.runtimeMinutes?.takeIf { item.isPlayable && it > 0 }?.let(::durationText),
    remainingText(item),
).joinToString(" · ")
