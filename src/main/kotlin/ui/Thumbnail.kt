package gallery.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import gallery.ImageLoader
import gallery.ImageSource
import gallery.i18n.LocalStrings
import gallery.i18n.StringsKey

@Composable
fun Thumbnail(source: ImageSource, name: String, targetPx: Int, modifier: Modifier = Modifier) {
    val colors = LocalGalleryColors.current
    // key 用 source 对象本身而不是 cacheKey：刷新压缩包后条目的 cacheKey 不变、但换了新的
    // reader，旧 reader 上没读完的那次加载会失败，只按 cacheKey 判断就永远不会重试。
    var bitmap by remember(source, targetPx) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(source, targetPx) { mutableStateOf(false) }
    LaunchedEffect(source, targetPx) {
        val b = ImageLoader.loadThumbnail(source, targetPx)
        bitmap = b
        // 解码失败 / 文件被删 / 压缩包打不开都返回 null；不单独记失败态的话，
        // 它和「还在加载」长得一样，骨架动画会一直闪下去。
        failed = b == null
    }
    val b = bitmap
    when {
        b != null -> Image(
            bitmap = b,
            contentDescription = name,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
        failed -> Box(modifier.background(colors.placeholder), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = LocalStrings.current.t(StringsKey.ThumbnailFailed),
                tint = colors.onSurfaceMuted,
                modifier = Modifier.size(28.dp),
            )
        }
        else -> {
            val transition = rememberInfiniteTransition()
            val alpha by transition.animateFloat(
                initialValue = 0.45f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            )
            Box(modifier.background(colors.placeholder.copy(alpha = alpha)))
        }
    }
}
