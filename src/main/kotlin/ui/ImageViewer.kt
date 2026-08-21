package gallery.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gallery.ImageItem
import gallery.ImageLoader
import gallery.LoadedImage
import gallery.formatBytes
import gallery.openInFileManager
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ZoomState {
    var scale by mutableStateOf(1f)
    var offsetX by mutableStateOf(0f)
    var offsetY by mutableStateOf(0f)
    var fit by mutableStateOf(1f)

    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    fun zoomBy(factor: Float) {
        scale = (scale * factor).coerceIn(0.02f, 64f)
        if (scale <= 1f) {
            offsetX = 0f
            offsetY = 0f
        }
    }

    fun to100() {
        if (fit > 0f) scale = (1f / fit).coerceIn(0.02f, 64f)
    }

    fun toFit() = reset()
}

@Composable
fun ImageViewer(
    item: ImageItem,
    index: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onToggleInfo: () -> Unit,
    showInfo: Boolean,
    showUi: Boolean,
    onToggleUi: () -> Unit,
    onToggleSlideshow: () -> Unit,
    slideshow: Boolean,
) {
    var loadedImage by remember(item) { mutableStateOf<LoadedImage?>(null) }
    val zoom = remember { ZoomState() }
    LaunchedEffect(item) {
        zoom.reset()
        loadedImage = ImageLoader.loadFull(item.source)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val li = loadedImage
        when {
            li == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Accent)
                        Spacer(Modifier.height(8.dp))
                        Text("加载中…", color = Color(0xFFAAAAAA), fontSize = 13.sp)
                    }
                }
            }
            li is LoadedImage.Static -> {
                ZoomableImage(
                    bitmap = li.bitmap,
                    zoom = zoom,
                    modifier = Modifier.fillMaxSize(),
                    onTap = onToggleUi,
                    onDoubleTap = { zoom.toFit() },
                )
            }
            li is LoadedImage.Animated -> {
                val frames = li.animation.frames
                if (frames.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("无法解码此动画", color = Color(0xFFAAAAAA))
                    }
                } else {
                    var frameIndex by remember(li.animation) { mutableStateOf(0) }
                    var loopsCompleted by remember(li.animation) { mutableStateOf(0) }
                    val delays = li.animation.delaysMs
                    val maxLoops = if (li.animation.loopCount > 0) li.animation.loopCount else null

                    LaunchedEffect(frames, delays, maxLoops) {
                        while (isActive && (maxLoops == null || loopsCompleted < maxLoops)) {
                            kotlinx.coroutines.delay(delays.getOrElse(frameIndex) { 100 }.toLong())
                            val nextIndex = (frameIndex + 1) % frames.size
                            if (nextIndex == 0) loopsCompleted++
                            frameIndex = nextIndex
                        }
                    }

                    ZoomableImage(
                        bitmap = frames[frameIndex],
                        zoom = zoom,
                        modifier = Modifier.fillMaxSize(),
                        onTap = onToggleUi,
                        onDoubleTap = { zoom.toFit() },
                    )
                }
            }
        }

        if (showUi) {
            ViewerTopBar(
                name = item.name,
                index = index,
                total = total,
                slideshow = slideshow,
                onToggleSlideshow = onToggleSlideshow,
                onToggleInfo = onToggleInfo,
                onClose = onClose,
            )
            ViewerBottomBar(
                onPrev = onPrev,
                onNext = onNext,
                onFit = { zoom.toFit() },
                onActual = { zoom.to100() },
                onZoomIn = { zoom.zoomBy(1.25f) },
                onZoomOut = { zoom.zoomBy(1f / 1.25f) },
                onOpenInFolder = { openInFileManager(item.containerFile) },
                onDelete = onDelete,
                canDelete = item.file != null,
            )
        }

        if (showInfo) {
            InfoPanel(item, loadedImage)
        }
    }
}

@Composable
private fun BoxScope.ViewerTopBar(
    name: String,
    index: Int,
    total: Int,
    slideshow: Boolean,
    onToggleSlideshow: () -> Unit,
    onToggleInfo: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("${index + 1} / $total", color = Color(0xFFBBBBBB), fontSize = 13.sp)
        IconButton(onClick = onToggleSlideshow) {
            Icon(Icons.Filled.PlayArrow, "幻灯片", tint = if (slideshow) Accent else Color.White)
        }
        IconButton(onClick = onToggleInfo) {
            Icon(Icons.Filled.Info, "信息", tint = Color.White)
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, "关闭", tint = Color.White)
        }
    }
}

@Composable
private fun BoxScope.ViewerBottomBar(
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFit: () -> Unit,
    onActual: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onOpenInFolder: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean = true,
) {
    Row(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Filled.KeyboardArrowLeft, "上一张", tint = Color.White)
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.KeyboardArrowRight, "下一张", tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onFit) { Text("适应", color = Color.White) }
        TextButton(onClick = onActual) { Text("1:1", color = Color.White) }
        TextButton(onClick = onZoomOut) { Text("−", color = Color.White, fontSize = 16.sp) }
        TextButton(onClick = onZoomIn) { Text("+", color = Color.White, fontSize = 16.sp) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onOpenInFolder) {
            Icon(Icons.Filled.Search, "在文件夹中显示", tint = Color.White)
        }
        IconButton(onClick = onDelete, enabled = canDelete) {
            Icon(Icons.Filled.Delete, "删除", tint = if (canDelete) Color.White else Color(0xFF666666))
        }
    }
}

@Composable
private fun BoxScope.InfoPanel(item: ImageItem, loadedImage: LoadedImage?) {
    Surface(
        Modifier.align(Alignment.CenterEnd).width(300.dp).fillMaxHeight(),
        color = Color(0xE6000000),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("文件信息", color = Accent, fontSize = 14.sp)
            InfoRow("名称", item.name)
            InfoRow("尺寸", "${loadedImage?.width ?: 0} × ${loadedImage?.height ?: 0} px")
            InfoRow("大小", formatBytes(item.sizeBytes))
            InfoRow("路径", item.displayPath)
            InfoRow(
                "修改时间",
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(item.modified)),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, color = Color(0xFF999999), fontSize = 11.sp)
        Text(value, color = Color(0xFFEEEEEE), fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ZoomableImage(
    bitmap: ImageBitmap,
    zoom: ZoomState,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
) {
    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val fit = remember(bitmap, boxW, boxH) {
            if (bitmap.width > 0 && bitmap.height > 0 && boxW > 0 && boxH > 0) {
                min(boxW / bitmap.width, boxH / bitmap.height)
            } else {
                1f
            }
        }
        SideEffect { zoom.fit = fit }

        fun zoomAt(factor: Float, focal: Offset) {
            val oldScale = zoom.scale
            val newScale = (oldScale * factor).coerceIn(0.02f, 64f)
            val k = newScale / oldScale
            val drawnW = bitmap.width * fit * oldScale
            val drawnH = bitmap.height * fit * oldScale
            val imgLeft = (boxW - drawnW) / 2f + zoom.offsetX
            val imgTop = (boxH - drawnH) / 2f + zoom.offsetY
            val newDrawnW = bitmap.width * fit * newScale
            val newDrawnH = bitmap.height * fit * newScale
            zoom.offsetX = (focal.x - (focal.x - imgLeft) * k) - (boxW - newDrawnW) / 2f
            zoom.offsetY = (focal.y - (focal.y - imgTop) * k) - (boxH - newDrawnH) / 2f
            zoom.scale = newScale
            if (newScale <= 1f) {
                zoom.offsetX = 0f
                zoom.offsetY = 0f
            }
        }

        val drawnW = bitmap.width * fit * zoom.scale
        val drawnH = bitmap.height * fit * zoom.scale
        val imgLeft = (boxW - drawnW) / 2f + zoom.offsetX
        val imgTop = (boxH - drawnH) / 2f + zoom.offsetY

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    detectTransformGestures { centroid, pan, zoomChange, _ ->
                        if (zoomChange != 1f) {
                            zoomAt(zoomChange, centroid)
                        }
                        zoom.offsetX += pan.x
                        zoom.offsetY += pan.y
                        if (zoom.scale <= 1f) {
                            zoom.offsetX = 0f
                            zoom.offsetY = 0f
                        }
                    }
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.first()
                    val dy = change.scrollDelta.y
                    if (dy != 0f) {
                        zoomAt(if (dy > 0) 1.15f else 1f / 1.15f, change.position)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { onDoubleTap() },
                    )
                },
        ) {
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(imgLeft.roundToInt(), imgTop.roundToInt()),
                dstSize = IntSize(max(1, drawnW.roundToInt()), max(1, drawnH.roundToInt())),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}
