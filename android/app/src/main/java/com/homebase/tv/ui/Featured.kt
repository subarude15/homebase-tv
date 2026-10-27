package com.homebase.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.homebase.tv.data.backend.MediaItem
import com.homebase.tv.data.backend.imageUrl
import com.homebase.tv.ui.theme.Charcoal

/** Fixed height so rows below don't shift when the synopsis length changes. */
val FeaturedInfoHeight = 128.dp

@Composable
fun FeaturedBackdrop(item: MediaItem?, backendUrl: String, modifier: Modifier = Modifier) {
    val url = item?.backdrop?.let { imageUrl(backendUrl, it, width = 1280) }
    // The previous backdrop stays up until the next one has loaded, so focus moves never flash to blank.
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url) { if (url == null) shown = null }
    Box(modifier) {
        shown?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (url != null && url != shown) {
            AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onSuccess = { shown = url })
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Charcoal, 0.35f to Charcoal.copy(alpha = 0.6f), 0.75f to Color.Transparent),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Charcoal.copy(alpha = 0.7f), 0.2f to Color.Transparent, 0.5f to Color.Transparent, 1f to Charcoal),
            ),
        )
    }
}

@Composable
fun FeaturedInfo(item: MediaItem, modifier: Modifier = Modifier) {
    Column(modifier.height(FeaturedInfoHeight).width(540.dp)) {
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.headlineSmall)
        featuredDetail(item).takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            item.overview,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
    }
}
