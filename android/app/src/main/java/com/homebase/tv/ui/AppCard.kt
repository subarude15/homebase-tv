package com.homebase.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.homebase.tv.data.apps.InstalledApp
import com.homebase.tv.ui.theme.Accent

@Composable
fun AppCard(
    app: InstalledApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    width: Dp = 196.dp,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.width(width).aspectRatio(16f / 9f),
        scale = CardDefaults.scale(focusedScale = 1.08f),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (app.isBanner) {
                Image(app.image, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Image(app.image, null, Modifier.size(48.dp))
                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (isFavorite) {
                Text("★", Modifier.align(Alignment.TopEnd).padding(6.dp), color = Accent)
            }
        }
    }
}
