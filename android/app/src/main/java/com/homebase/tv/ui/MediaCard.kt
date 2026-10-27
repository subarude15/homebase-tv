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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.homebase.tv.data.backend.MediaItem
import com.homebase.tv.data.backend.Shape
import com.homebase.tv.ui.theme.Accent

private fun Shape.size() = when (this) {
    Shape.Landscape -> 260.dp to 16f / 9f
    Shape.Poster -> 140.dp to 2f / 3f
    Shape.Square -> 180.dp to 1f
}

@Composable
fun MediaCard(item: MediaItem, shape: Shape, backendUrl: String, onClick: () -> Unit) {
    val (width, ratio) = shape.size()
    Column(Modifier.width(width)) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(ratio)) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (item.image != null) {
                    AsyncImage(
                        model = backendUrl.trim().trimEnd('/') + item.image,
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
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)
                            .background(Color.Black.copy(alpha = 0.6f)),
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Accent))
                    }
                }
            }
        }
        Text(
            item.title,
            // Clears the card's focus scale-up so the title isn't covered.
            Modifier.padding(top = 14.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (item.subtitle.isNotBlank()) {
            Text(
                item.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
