package gallery.ui

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF4FC3F7)
val Danger = Color(0xFFE57373)

@Composable
fun GalleryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = darkColors(
            primary = Accent,
            secondary = Accent,
            background = Color(0xFF101010),
            surface = Color(0xFF1C1C1E),
            onBackground = Color(0xFFE6E6E6),
            onSurface = Color(0xFFE6E6E6),
            error = Danger,
        ),
        content = content,
    )
}
