package gallery.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import gallery.ImageLoader
import gallery.ImageSource

@Composable
fun Thumbnail(source: ImageSource, name: String, targetPx: Int, modifier: Modifier = Modifier) {
    val colors = LocalGalleryColors.current
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
        val transition = rememberInfiniteTransition()
        val alpha by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        )
        Box(modifier.background(colors.placeholder.copy(alpha = alpha)))
    }
}
