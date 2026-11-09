package io.github.hussainhz.mangatv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

val Accent = Color(0xFFFF7A2F)
val Background = Color(0xFF101014)
val SurfaceColor = Color(0xFF1C1C22)

@Composable
fun MangaTheme(content: @Composable () -> Unit) {
    // tv-material for focusable TV components, Material 3 for the text fields.
    M3Theme(
        colorScheme = m3DarkColorScheme(
            primary = Accent, background = Background, surface = SurfaceColor,
            onPrimary = Color.Black, onSurface = Color.White, onBackground = Color.White,
        ),
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Accent,
                onPrimary = Color.Black,
                background = Background,
                surface = SurfaceColor,
                onSurface = Color.White,
                onBackground = Color.White,
                surfaceVariant = Color(0xFF2A2A33),
                onSurfaceVariant = Color(0xFFCFCFD8),
                border = Accent,
            ),
            content = content,
        )
    }
}
