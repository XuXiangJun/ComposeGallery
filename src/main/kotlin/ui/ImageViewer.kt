package gallery.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import gallery.copyImageToClipboard
import gallery.openItemWithDefaultApp
import java.awt.Toolkit
import java.awt.image.BufferedImage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gallery.ImageItem
import gallery.ReadingDirection
import gallery.WheelAction
import gallery.ImageLoader
import gallery.LoadedImage
import gallery.formatBytes
import gallery.openInFileManager
import gallery.i18n.LocalStrings
import gallery.i18n.StringsKey
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /** 顺时针旋转角度（0 / 90 / 180 / 270）与水平翻转；只影响显示，不改文件。 */
    var rotation by mutableStateOf(0)
    var flipped by mutableStateOf(false)

    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    /** 换图时调用：连旋转 / 翻转一起复位（双击「适应」只复位缩放，保留旋转）。 */
    fun resetAll() {
        reset()
        rotation = 0
        flipped = false
    }

    fun rotateBy(degrees: Int) {
        rotation = ((rotation + degrees) % 360 + 360) % 360
        reset()
    }

    fun toggleFlip() {
        flipped = !flipped
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

@OptIn(ExperimentalComposeUiApi::class)
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
    readingDirection: ReadingDirection,
    onToggleReadingDirection: () -> Unit,
    wheelAction: WheelAction,
    onToggleWheelAction: () -> Unit,
    /** 全屏时鼠标静止一会儿就自动隐藏工具栏和指针，看图不被遮挡。 */
    fullscreen: Boolean = false,
) {
    // 从右往左读（日漫）时，屏幕左侧 = 下一页。底栏箭头、点击区域都按屏幕方位走，
    // 这样「点哪边就往哪边翻」和书页的物理方向一致。
    val rtl = readingDirection == ReadingDirection.RTL
    val onLeft = if (rtl) onNext else onPrev
    val onRight = if (rtl) onPrev else onNext
    val onWheelPage: ((Int) -> Unit)? =
        if (wheelAction == WheelAction.PAGE) { d -> if (d > 0) onNext() else onPrev() } else null

    // 缓存命中（前后预取过的页）时组合阶段就直接拿到图，不再先渲染一帧「加载中」再换图。
    var loadedImage by remember(item) { mutableStateOf(ImageLoader.peekFull(item.source)) }
    var loadFailed by remember(item) { mutableStateOf(false) }
    val zoom = remember { ZoomState() }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1500)
            toast = null
        }
    }
    var pointerIdle by remember { mutableStateOf(false) }
    // 鼠标移动走 conflated channel 而不是 State 计数器：原先每个 Move 事件 moveTick++，
    // 而 moveTick 是本组件作用域读的 LaunchedEffect key —— 整个查看器（含图片绘制）按鼠标
    // 移动频率重组，非全屏时也一样。现在只有 pointerIdle 真的翻转时才重组。
    val pointerMoves = remember { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(fullscreen) {
        pointerIdle = false
        if (!fullscreen) return@LaunchedEffect
        pointerMoves.receiveAsFlow().onStart { emit(Unit) }.collectLatest {
            pointerIdle = false
            delay(AUTO_HIDE_DELAY_MS)
            pointerIdle = true
        }
    }
    val autoHidden = fullscreen && pointerIdle
    val strings = LocalStrings.current

    /** 当前显示的那一帧（动画取首帧），用于复制到剪贴板。 */
    fun currentBitmap(): ImageBitmap? = when (val li = loadedImage) {
        is LoadedImage.Static -> li.bitmap
        is LoadedImage.Animated -> li.animation.frames.firstOrNull()
        null -> null
    }

    fun copyCurrent() {
        val b = currentBitmap() ?: return
        toast = strings.t(if (copyImageToClipboard(b.toAwtImage())) StringsKey.Copied else StringsKey.CopyFailed)
    }

    LaunchedEffect(item) {
        zoom.resetAll()
        loadFailed = false
        if (loadedImage == null) loadedImage = ImageLoader.loadFull(item.source)
        // loadFull 内部 runCatching{}.getOrNull()：解码失败、文件被删、不可读都返回 null。
        // 不单独记一个失败态的话，这三种情况和「还在加载」在 UI 上完全一样 —— 静态图会永远转圈，
        // 而 GIF 分支早就有 LoadAnimationFailed 了，这里补上对齐。
        if (loadedImage == null) loadFailed = true
        focusRequester.requestFocus()
    }

    // 翻页 / 关闭 / 删除等键盘操作统一由 App 的 onPreviewKeyEvent 处理（窗口根部、预览阶段
    // 最先拿到）。只有缩放快捷键在这里处理：缩放状态 [zoom] 属于查看器，App 碰不到，
    // App 对这些键返回 false，事件就会继续传到获得焦点的这里。
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPointerEvent(PointerEventType.Move) { pointerMoves.trySend(Unit) }
            .pointerHoverIcon(if (autoHidden) BlankPointer else PointerIcon.Default)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.C -> if (e.isCtrlPressed || e.isMetaPressed) { copyCurrent(); true } else false
                    Key.R -> { zoom.rotateBy(if (e.isShiftPressed) -90 else 90); true }
                    Key.H -> { zoom.toggleFlip(); true }
                    Key.Equals, Key.Plus, Key.NumPadAdd -> { zoom.zoomBy(1.25f); true }
                    Key.Minus, Key.NumPadSubtract -> { zoom.zoomBy(1f / 1.25f); true }
                    Key.Zero, Key.NumPad0 -> { zoom.toFit(); true }
                    Key.One, Key.NumPad1 -> { zoom.to100(); true }
                    else -> false
                }
            },
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
                    onTapLeft = onLeft,
                    onTapRight = onRight,
                    onTapCenter = onToggleUi,
                    onDoubleTap = { zoom.toFit() },
                    onWheelPage = onWheelPage,
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
                            delay(delays.getOrElse(frameIndex) { 100 }.toLong())
                            val nextIndex = (frameIndex + 1) % frames.size
                            if (nextIndex == 0) loopsCompleted++
                            frameIndex = nextIndex
                        }
                    }

                    ZoomableImage(
                        bitmap = frames[frameIndex],
                        zoom = zoom,
                        modifier = Modifier.fillMaxSize(),
                        onTapLeft = onLeft,
                        onTapRight = onRight,
                        onTapCenter = onToggleUi,
                        onDoubleTap = { zoom.toFit() },
                        onWheelPage = onWheelPage,
                    )
                }
            }
        }

        // 信息面板必须在工具栏**之前**声明：Compose 里后声明的在上层，而面板占满右侧全高，
        // 一旦盖住顶栏的「信息」按钮，点击就会落在面板上，用户再也关不掉它。
        if (showInfo) {
            InfoPanel(item, loadedImage, onClose = onToggleInfo)
        }

        toast?.let { msg ->
            Text(
                msg,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = ViewerBarInset + 16.dp)
                    .background(ViewerPanelScrim, RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        if (showUi && !autoHidden) {
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
                onLeft = onLeft,
                onRight = onRight,
                readingDirection = readingDirection,
                onToggleReadingDirection = onToggleReadingDirection,
                wheelAction = wheelAction,
                onToggleWheelAction = onToggleWheelAction,
                onFit = { zoom.toFit() },
                onActual = { zoom.to100() },
                onZoomIn = { zoom.zoomBy(1.25f) },
                onZoomOut = { zoom.zoomBy(1f / 1.25f) },
                // 两者都可能阻塞（解出整条压缩包条目 / 等外部进程退出），不能放在 UI 线程上。
                onOpenInFolder = { scope.launch(Dispatchers.IO) { openInFileManager(item.containerFile) } },
                onOpenExternal = { scope.launch(Dispatchers.IO) { openItemWithDefaultApp(item) } },
                onRotate = { zoom.rotateBy(90) },
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
            Icon(if (slideshow) PauseIcon else Icons.Filled.PlayArrow, s.t(StringsKey.SlideShow), tint = if (slideshow) Accent else ViewerIcon)
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
    onLeft: () -> Unit,
    onRight: () -> Unit,
    readingDirection: ReadingDirection,
    onToggleReadingDirection: () -> Unit,
    wheelAction: WheelAction,
    onToggleWheelAction: () -> Unit,
    onFit: () -> Unit,
    onActual: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onOpenInFolder: () -> Unit,
    onOpenExternal: () -> Unit,
    onRotate: () -> Unit,
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
        val rtl = readingDirection == ReadingDirection.RTL
        IconButton(onClick = onLeft) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, s.t(if (rtl) StringsKey.Next else StringsKey.Prev), tint = ViewerIcon)
        }
        IconButton(onClick = onRight) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, s.t(if (rtl) StringsKey.Prev else StringsKey.Next), tint = ViewerIcon)
        }
        TextButton(onClick = onToggleReadingDirection) {
            Text(s.t(if (rtl) StringsKey.DirectionRtl else StringsKey.DirectionLtr), color = ViewerIcon, fontSize = 12.sp)
        }
        TextButton(onClick = onToggleWheelAction) {
            Text(
                s.t(if (wheelAction == WheelAction.PAGE) StringsKey.WheelPage else StringsKey.WheelZoom),
                color = ViewerIcon,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onFit) { Text(s.t(StringsKey.Fit), color = ViewerIcon) }
        TextButton(onClick = onActual) { Text(s.t(StringsKey.Actual), color = ViewerIcon) }
        TextButton(onClick = onZoomOut, modifier = Modifier.semantics { contentDescription = s.t(StringsKey.A11yZoomOut) }) {
            Text("−", color = ViewerIcon, fontSize = 16.sp)
        }
        TextButton(onClick = onZoomIn, modifier = Modifier.semantics { contentDescription = s.t(StringsKey.A11yZoomIn) }) {
            Text("+", color = ViewerIcon, fontSize = 16.sp)
        }
        TextButton(onClick = onRotate, modifier = Modifier.semantics { contentDescription = s.t(StringsKey.Rotate) }) {
            Text("⟳", color = ViewerIcon, fontSize = 16.sp)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onOpenInFolder) {
            Icon(Icons.Filled.LocationOn, s.t(StringsKey.OpenInFolder), tint = ViewerIcon)
        }
        IconButton(onClick = onOpenExternal) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, s.t(StringsKey.OpenExternal), tint = ViewerIcon)
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

/** 全屏下鼠标静止多久后隐藏工具栏和指针。 */
private const val AUTO_HIDE_DELAY_MS = 2500L

/** 透明指针（全屏自动隐藏时用）。懒加载：无头环境（单元测试）里不会碰 AWT Toolkit。 */
private val BlankPointer: PointerIcon by lazy {
    PointerIcon(
        Toolkit.getDefaultToolkit().createCustomCursor(
            BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "blank",
        ),
    )
}

/** material-icons-core 里没有 Pause，自己画一个（两条竖杠）。 */
private val PauseIcon: ImageVector by lazy {
    ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 5f); horizontalLineTo(10f); verticalLineTo(19f); horizontalLineTo(6f); close()
            moveTo(14f, 5f); horizontalLineTo(18f); verticalLineTo(19f); horizontalLineTo(14f); close()
        }
    }.build()
}

/** 滚轮翻页的最小间隔：触控板一次滑动会连发几十个小 delta，不节流就会一口气翻过好几页。 */
private const val WHEEL_PAGE_INTERVAL_MS = 250L

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ZoomableImage(
    bitmap: ImageBitmap,
    zoom: ZoomState,
    modifier: Modifier = Modifier,
    onTapLeft: () -> Unit = {},
    onTapRight: () -> Unit = {},
    onTapCenter: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    /** 非 null 时滚轮用来翻页（参数 +1 = 下一页），按住 Ctrl 仍是缩放；null 时滚轮缩放。 */
    onWheelPage: ((Int) -> Unit)? = null,
) {
    // pointerInput(Unit) 里的协程不会随重组重启，直接捕获回调会一直用首次组合时的旧 lambda
    // （切换阅读方向后点击区域不跟着变）。经 rememberUpdatedState 读永远是最新的。
    val tapLeft by rememberUpdatedState(onTapLeft)
    val tapRight by rememberUpdatedState(onTapRight)
    val tapCenter by rememberUpdatedState(onTapCenter)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val wheelPage by rememberUpdatedState(onWheelPage)
    val lastWheelPage = remember { longArrayOf(0L) }

    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        // 旋转 90° / 270° 时，按「转过之后」的宽高来适应视口。
        val swapped = zoom.rotation % 180 != 0
        val bw = if (swapped) bitmap.height else bitmap.width
        val bh = if (swapped) bitmap.width else bitmap.height
        val fit = remember(bitmap, boxW, boxH, swapped) {
            if (bw > 0 && bh > 0 && boxW > 0 && boxH > 0) {
                min(boxW / bw, boxH / bh)
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
            zoom.baseW = bw * fit
            zoom.baseH = bh * fit
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
                    if (dy == 0f) return@onPointerEvent
                    val page = wheelPage
                    if (page != null && !event.keyboardModifiers.isCtrlPressed) {
                        val now = System.currentTimeMillis()
                        if (now - lastWheelPage[0] >= WHEEL_PAGE_INTERVAL_MS) {
                            lastWheelPage[0] = now
                            page(if (dy > 0) 1 else -1)
                        }
                    } else {
                        // 向上滚（scrollDelta.y 为负）= 放大，与主流看图软件一致；
                        // 想反过来只需把这里的判断改成 dy > 0。
                        zoom.zoomAt(if (dy < 0) 1.15f else 1f / 1.15f, change.position)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        // 左右各三分之一是翻页区，中间点一下显示 / 隐藏工具栏。
                        onTap = { pos ->
                            val w = size.width
                            when {
                                pos.x < w / 3f -> tapLeft()
                                pos.x > w * 2f / 3f -> tapRight()
                                else -> tapCenter()
                            }
                        },
                        onDoubleTap = { doubleTap() },
                    )
                },
        ) {
            // 以图片中心为轴：先按未旋转的尺寸摆好，再旋转；翻转放在最外层，
            // 这样「水平翻转」永远是屏幕上的左右翻，而不是图片自身坐标系里的翻。
            val center = Offset(imgLeft + drawnW / 2f, imgTop + drawnH / 2f)
            val dw = if (swapped) drawnH else drawnW
            val dh = if (swapped) drawnW else drawnH
            withTransform({
                if (zoom.flipped) scale(-1f, 1f, center)
                rotate(zoom.rotation.toFloat(), center)
            }) {
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset((center.x - dw / 2f).roundToInt(), (center.y - dh / 2f).roundToInt()),
                    dstSize = IntSize(max(1, dw.roundToInt()), max(1, dh.roundToInt())),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}
