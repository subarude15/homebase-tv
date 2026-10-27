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

@Composable
fun HomebaseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Color(0xFF0E1621),
            surface = Color(0xFF0E1621),
        ),
    ) {
        Surface(Modifier.fillMaxSize(), shape = RectangleShape) { content() }
    }
}
