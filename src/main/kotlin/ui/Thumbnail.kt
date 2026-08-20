package gallery.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import gallery.ImageLoader
import gallery.ImageSource

@Composable
fun Thumbnail(source: ImageSource, name: String, targetPx: Int, modifier: Modifier = Modifier) {
    var bitmap by remember(source.cacheKey, targetPx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(source.cacheKey, targetPx) {
        bitmap = ImageLoader.loadThumbnail(source, targetPx)
    }
    val b = bitmap
    if (b != null) {
        Image(
            bitmap = b,
            contentDescription = name,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier.background(Color(0xFF2A2A2D)))
    }
}
