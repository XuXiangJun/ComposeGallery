package gallery.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.Slider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import gallery.ui.ViewerBackground
import gallery.ui.ViewerBody
import gallery.ui.ViewerCaption
import gallery.ui.ViewerDim
import gallery.ui.ViewerIcon
import gallery.ui.ViewerIconSurface
import gallery.ui.ViewerMuted
import gallery.ui.ViewerPanelScrim
import gallery.ui.ViewerScrim
import gallery.ui.ViewerSubtle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import gallery.i18n.LocalStrings
import gallery.i18n.StringsKey
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 信息面板的「修改时间」格式：复用同一实例，避免每次重组都 new（仅 UI 线程使用）。 */
private val infoDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

/**
 * 顶部 / 底部工具栏的高度（顶栏 = IconButton 48dp + 上下各 4dp，底栏略矮）。
 * 信息面板要按这个值留白，否则内容会被工具栏压住。
 */
private val ViewerBarInset = 56.dp

/**
 * 查看器的缩放 / 平移状态。
 *
 * 视口尺寸与「基础绘制尺寸」也放在这里，而不是让事件回调去捕获组合期的局部变量：
 * 滚轮 / 手势回调注册在 pointerInput 上，key 不变时那个协程不会重启，
 * 回调里捕获的局部值会一直是**首次组合**时的旧值（渲染却用最新值），
 * 缩放锚点一旦算错就会「越滚越偏」。放进对象字段则永远是当前值。
 */
class ZoomState {
    var scale by mutableStateOf(1f)
    var offsetX by mutableStateOf(0f)
    var offsetY by mutableStateOf(0f)
    var fit by mutableStateOf(1f)

    /** 视口（画布）尺寸，由布局阶段写入。 */
    var viewportW by mutableStateOf(0f)
    var viewportH by mutableStateOf(0f)

    /** 基础绘制尺寸 = bitmap 尺寸 × fit，由布局阶段写入。 */
    var baseW by mutableStateOf(0f)
    var baseH by mutableStateOf(0f)

    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    /** 当前 [s] 缩放下图片左上角在视口中的位置。 */
    private fun imageLeft(s: Float): Float = (viewportW - baseW * s) / 2f + offsetX
    private fun imageTop(s: Float): Float = (viewportH - baseH * s) / 2f + offsetY

    /**
     * 以 [focal]（视口坐标）为锚点缩放：该点下的图片内容尽量保持不动。
     * 若该锚点要求把图片推出视口，则由 [clampToViewport] 收回来（见其说明）。
     */
    fun zoomAt(factor: Float, focal: Offset) {
        if (baseW <= 0f || baseH <= 0f || viewportW <= 0f || viewportH <= 0f) return
        val oldScale = scale
        val newScale = (oldScale * factor).coerceIn(0.02f, 64f)
        if (newScale == oldScale) return
        val k = newScale / oldScale
        val left = imageLeft(oldScale)
        val top = imageTop(oldScale)
        offsetX = (focal.x - (focal.x - left) * k) - (viewportW - baseW * newScale) / 2f
        offsetY = (focal.y - (focal.y - top) * k) - (viewportH - baseH * newScale) / 2f
        scale = newScale
        clampToViewport()
    }

    /** 拖拽平移。 */
    fun panBy(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        offsetX += dx
        offsetY += dy
        clampToViewport()
    }

    /** 按钮缩放：以视口中心为锚点（所以只改 scale，不动 offset）。 */
    fun zoomBy(factor: Float) {
        scale = (scale * factor).coerceIn(0.02f, 64f)
        clampToViewport()
    }

    fun to100() {
        if (fit > 0f) scale = (1f / fit).coerceIn(0.02f, 64f)
    }

    fun toFit() = reset()

    /**
     * 把平移量收进合理范围：图片比视口大时，边缘不能被推出视口（不留黑边）；
     * 比视口小时居中。
     *
     * 没有这一步，以鼠标为锚点的缩放会顺着鼠标方向把图片一路推出视口 ——
     * 表现就是「鼠标在界面两边滚动，图片往两边跑，越滚越偏」。
     */
    private fun clampToViewport() {
        offsetX = clampAxis(offsetX, baseW * scale, viewportW)
        offsetY = clampAxis(offsetY, baseH * scale, viewportH)
    }

    private fun clampAxis(offset: Float, drawn: Float, viewport: Float): Float {
        if (drawn <= viewport) return 0f
        val limit = (drawn - viewport) / 2f
        return offset.coerceIn(-limit, limit)
    }

    /** 视口坐标 [p] 对应的图片像素坐标（相对图片左上角）；用于诊断与测试锚点是否稳定。 */
    fun imagePointAt(p: Offset): Offset =
        Offset((p.x - imageLeft(scale)) / scale, (p.y - imageTop(scale)) / scale)
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
    slideshowSeconds: Float,
    onSlideshowSecondsChange: (Float) -> Unit,
) {
    var loadedImage by remember(item) { mutableStateOf<LoadedImage?>(null) }
    var loadFailed by remember(item) { mutableStateOf(false) }
    val zoom = remember { ZoomState() }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(item) {
        zoom.reset()
        loadedImage = null
        loadFailed = false
        loadedImage = ImageLoader.loadFull(item.source)
        // loadFull 内部 runCatching{}.getOrNull()：解码失败、文件被删、不可读都返回 null。
        // 不单独记一个失败态的话，这三种情况和「还在加载」在 UI 上完全一样 —— 静态图会永远转圈，
        // 而 GIF 分支早就有 LoadAnimationFailed 了，这里补上对齐。
        if (loadedImage == null) loadFailed = true
        focusRequester.requestFocus()
    }

    // 键盘（←/→/Esc/Delete/I/空格/F11/F1）统一由 App 的 onPreviewKeyEvent 处理：
    // 它在窗口根部、预览阶段最先拿到事件，这里再处理一遍只会是死代码。
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        val li = loadedImage
        val s = LocalStrings.current
        when {
            // 失败态必须排在 li == null 之前：两者 loadedImage 都是 null，靠 loadFailed 区分。
            loadFailed -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.t(StringsKey.LoadFailed), color = ViewerMuted, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.name,
                            color = ViewerDim,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            li == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Accent)
                        Spacer(Modifier.height(8.dp))
                        Text(s.t(StringsKey.Loading), color = ViewerMuted, fontSize = 13.sp)
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
                        Text(s.t(StringsKey.LoadAnimationFailed), color = ViewerMuted)
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

        // 信息面板必须在工具栏**之前**声明：Compose 里后声明的在上层，而面板占满右侧全高，
        // 一旦盖住顶栏的「信息」按钮，点击就会落在面板上，用户再也关不掉它。
        if (showInfo) {
            InfoPanel(item, loadedImage, onClose = onToggleInfo)
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
                slideshowSeconds = slideshowSeconds,
                onSlideshowSecondsChange = onSlideshowSecondsChange,
            )
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
    val s = LocalStrings.current
    Row(
        Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .background(ViewerScrim)
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
        Text("${index + 1} / $total", color = ViewerSubtle, fontSize = 13.sp)
        IconButton(onClick = onToggleSlideshow) {
            Icon(Icons.Filled.PlayArrow, s.t(StringsKey.SlideShow), tint = if (slideshow) Accent else ViewerIcon)
        }
        IconButton(onClick = onToggleInfo) {
            Icon(Icons.Filled.Info, s.t(StringsKey.Info), tint = ViewerIcon)
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, s.t(StringsKey.Close), tint = ViewerIcon)
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
    slideshowSeconds: Float,
    onSlideshowSecondsChange: (Float) -> Unit,
) {
    val s = LocalStrings.current
    Row(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(ViewerScrim)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, s.t(StringsKey.Prev), tint = ViewerIcon)
        }
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, s.t(StringsKey.Next), tint = ViewerIcon)
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onFit) { Text(s.t(StringsKey.Fit), color = ViewerIcon) }
        TextButton(onClick = onActual) { Text(s.t(StringsKey.Actual), color = ViewerIcon) }
        TextButton(onClick = onZoomOut) { Text("−", color = ViewerIcon, fontSize = 16.sp) }
        TextButton(onClick = onZoomIn) { Text("+", color = ViewerIcon, fontSize = 16.sp) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onOpenInFolder) {
            Icon(Icons.Filled.Search, s.t(StringsKey.OpenInFolder), tint = ViewerIcon)
        }
        // 拖动过程中只更新本地状态，松手（onValueChangeFinished）才回调上层 —— 上层会写盘，
        // 这样避免滑块每移动一格就写一次 settings.json。
        var sliderValue by remember(slideshowSeconds) { mutableStateOf(slideshowSeconds) }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = 1f..10f,
            onValueChangeFinished = { onSlideshowSecondsChange(sliderValue) },
            modifier = Modifier.width(90.dp),
        )
        Text(s.t(StringsKey.Interval, sliderValue.toInt()), color = ViewerIcon, fontSize = 12.sp)
        IconButton(onClick = onDelete, enabled = canDelete) {
            Icon(Icons.Filled.Delete, s.t(StringsKey.Delete), tint = if (canDelete) ViewerIcon else ViewerDim)
        }
    }
}

@Composable
private fun BoxScope.InfoPanel(item: ImageItem, loadedImage: LoadedImage?, onClose: () -> Unit) {
    val s = LocalStrings.current
    Surface(
        Modifier
            .align(Alignment.CenterEnd)
            .width(300.dp)
            .fillMaxHeight()
            // 吃掉面板区域的点击，避免穿透到底下的图片 —— 那会触发「点一下隐藏工具栏」，
            // 结果用户想点面板却把工具栏点没了。
            .pointerInput(Unit) { detectTapGestures { } },
        color = ViewerPanelScrim,
    ) {
        Column(
            Modifier
                // 上下留出工具栏高度，内容才不会被顶栏 / 底栏压住。
                .padding(top = ViewerBarInset, bottom = ViewerBarInset, start = 16.dp, end = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(s.t(StringsKey.FileInfo), color = Accent, fontSize = 14.sp, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.height(28.dp).width(28.dp),
                ) {
                    Icon(Icons.Filled.Close, s.t(StringsKey.Close), tint = ViewerIcon, modifier = Modifier.height(16.dp).width(16.dp))
                }
            }
            InfoRow(s.t(StringsKey.InfoName), item.name)
            InfoRow(s.t(StringsKey.InfoDimensions), "${loadedImage?.width ?: 0} × ${loadedImage?.height ?: 0} px")
            InfoRow(s.t(StringsKey.InfoFileSize), formatBytes(item.sizeBytes))
            InfoRow(s.t(StringsKey.InfoPath), item.displayPath)
            InfoRow(s.t(StringsKey.InfoModified), infoDateFormat.format(Date(item.modified)))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val s = LocalStrings.current
    Column {
        Text(label, color = ViewerCaption, fontSize = 11.sp)
        Text(
            value,
            color = ViewerBody,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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

        // 几何量写给 ZoomState：事件回调读对象字段，永远是当前值。
        //（若让回调捕获这些局部变量，pointerInput 的协程不重启就会一直用旧值 → 缩放锚点漂移。）
        SideEffect {
            zoom.fit = fit
            zoom.viewportW = boxW
            zoom.viewportH = boxH
            zoom.baseW = bitmap.width * fit
            zoom.baseH = bitmap.height * fit
        }

        val drawnW = zoom.baseW * zoom.scale
        val drawnH = zoom.baseH * zoom.scale
        val imgLeft = (boxW - drawnW) / 2f + zoom.offsetX
        val imgTop = (boxH - drawnH) / 2f + zoom.offsetY

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    detectTransformGestures { centroid, pan, zoomChange, _ ->
                        if (zoomChange != 1f) zoom.zoomAt(zoomChange, centroid)
                        zoom.panBy(pan.x, pan.y)
                    }
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.first()
                    val dy = change.scrollDelta.y
                    if (dy != 0f) {
                        // 向上滚（scrollDelta.y 为负）= 放大，与主流看图软件一致；
                        // 想反过来只需把这里的判断改成 dy > 0。
                        zoom.zoomAt(if (dy < 0) 1.15f else 1f / 1.15f, change.position)
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
