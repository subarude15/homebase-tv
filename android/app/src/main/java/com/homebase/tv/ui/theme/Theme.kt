package com.homebase.tv.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme

val Accent = Color(0xFFF2A541)
val Charcoal = Color(0xFF141517)

@Composable
fun HomebaseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Charcoal,
            surface = Charcoal,
            surfaceVariant = Color(0xFF26282C),
            onSurface = Color(0xFFE8EAED),
            onSurfaceVariant = Color(0xFF9AA0A6),
        ),
    ) {
        Surface(Modifier.fillMaxSize(), shape = RectangleShape) { content() }
    }
}
