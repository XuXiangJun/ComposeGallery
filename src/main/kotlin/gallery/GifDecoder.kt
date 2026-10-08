package gallery

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.metadata.IIOMetadata
import javax.imageio.stream.ImageInputStream
import kotlin.math.sqrt
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.w3c.dom.Element

data class GifAnimation(
    val frames: List<ImageBitmap>,
    val delaysMs: List<Int>,
    val loopCount: Int, // 0 = 无限循环；>0 = 播几遍
    val width: Int,
    val height: Int,
)

object GifDecoder {
    /**
     * 动画所有帧的像素总预算。帧是全部常驻内存的（不像静态图能只留一张），
     * 超出预算时自动降低单帧分辨率——宁可模糊一点，也不要 OOM。
     */
    private const val MAX_TOTAL_PIXELS = 96L * 1024 * 1024

    /**
     * 合成画布的像素上限（4096²，ARGB 即 64MB；restoreToPrevious 还要再拷一份）。
     * 画布按 GIF 声明的逻辑尺寸分配，而 GIF 允许声明到 65535² —— 不设上限的话一张小文件
     * 就能让这里尝试分配十几 GB。超出时在缩小的画布上合成。
     */
    private const val MAX_CANVAS_PIXELS = 4096L * 4096

    /**
     * 解码动画 GIF 为帧列表。[maxDim] 限制单帧最大边长（大图按目标尺寸缩小，
     * 避免全尺寸加载过多内存）。帧直接用像素拷贝转成 Skia ImageBitmap（不经 PNG 中转）。
     */
    fun decode(bytes: ByteArray, maxDim: Int): GifAnimation? {
        return runCatching {
            ByteArrayInputStream(bytes).use { bis ->
                val imageInputStream: ImageInputStream = ImageIO.createImageInputStream(bis)
                    ?: return@use null
                val reader = ImageIO.getImageReadersByFormatName("gif").next()
                try {
                    reader.input = imageInputStream
                    decodeFrames(reader, maxDim)
                } finally {
                    reader.dispose()
                    imageInputStream.close()
                }
            }
        }.getOrNull()
    }

    /**
     * 逐帧解码并**合成**到逻辑画布上。
     *
     * ImageIO 的 `reader.read(i)` 只返回这一帧自己的矩形（Image Descriptor 里的
     * imageWidth × imageHeight），既不带 left/top 偏移，也不管上一帧的 disposal。
     * 网上大多数 GIF 都是差分帧（后续帧只更新变化的那一小块），直接把局部帧拉伸到
     * 整张画布就会花屏 / 跳动。所以这里维护一张全尺寸画布，按 GIF89a 规则合成：
     * - none / doNotDispose：下一帧直接画在当前画布上；
     * - restoreToBackgroundColor：下一帧开始前把本帧矩形清成透明；
     * - restoreToPrevious：下一帧开始前把画布恢复成画本帧之前的样子。
     */
    private fun decodeFrames(reader: ImageReader, maxDim: Int): GifAnimation {
        val loopCount = readLoopCount(reader)
        val (width, height) = readLogicalScreenSize(reader) ?: (reader.getWidth(0) to reader.getHeight(0))
        val numFrames = reader.getNumImages(true)

        // 单帧尺寸同时受 maxDim 与「总像素预算 / 帧数」两个约束。
        val limitScale = minOf(1f, maxDim.toFloat() / maxOf(width, height).toFloat())
        val perFrameBudget = MAX_TOTAL_PIXELS.toDouble() / numFrames.coerceAtLeast(1)
        val budgetScale = sqrt(perFrameBudget / (width.toDouble() * height.toDouble())).toFloat()
        // 画布通常就是逻辑尺寸（合成按像素精确对齐）；超出 MAX_CANVAS_PIXELS 才缩小。
        val canvasScale = minOf(1.0, sqrt(MAX_CANVAS_PIXELS.toDouble() / (width.toDouble() * height.toDouble())))
        val canvasW = maxOf(1, (width * canvasScale).toInt())
        val canvasH = maxOf(1, (height * canvasScale).toInt())
        // 输出帧不能比画布大（那只是把缩小过的画布再放大回去）。
        val scale = minOf(limitScale, budgetScale, canvasScale.toFloat()).coerceIn(0.01f, 1f)
        val frameW = minOf(canvasW, maxOf(1, (width * scale).toInt()))
        val frameH = minOf(canvasH, maxOf(1, (height * scale).toInt()))
        // 帧坐标（逻辑尺寸）→ 画布坐标；canvasScale == 1 时原样返回。
        fun toCanvas(v: Int) = (v * canvasScale).toInt()

        val canvas = BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB)
        val g = canvas.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val frames = ArrayList<ImageBitmap>(numFrames)
        val delays = ArrayList<Int>(numFrames)
        try {
            for (i in 0 until numFrames) {
                val meta = reader.getImageMetadata(i)
                val info = readFrameInfo(meta)
                val image = reader.read(i)

                // restoreToPrevious 需要画本帧**之前**的画布快照。
                val previous = if (info.disposal == "restoreToPrevious") copyOf(canvas) else null

                g.composite = AlphaComposite.SrcOver
                val x0 = toCanvas(info.left)
                val y0 = toCanvas(info.top)
                val x1 = toCanvas(info.left + image.width)
                val y1 = toCanvas(info.top + image.height)
                g.drawImage(image, x0, y0, x1 - x0, y1 - y0, null)
                frames.add(bufferedImageToImageBitmap(canvas, frameW, frameH))
                delays.add(info.delayMs)

                // 本帧的 disposal 作用于下一帧开始之前。
                when (info.disposal) {
                    "restoreToBackgroundColor" -> {
                        g.composite = AlphaComposite.Clear
                        g.fillRect(x0, y0, x1 - x0, y1 - y0)
                    }
                    "restoreToPrevious" -> if (previous != null) {
                        g.composite = AlphaComposite.Src
                        g.drawImage(previous, 0, 0, null)
                    }
                }
            }
        } finally {
            g.dispose()
        }
        return GifAnimation(frames, delays, loopCount, width, height)
    }

    private data class FrameInfo(val left: Int, val top: Int, val disposal: String, val delayMs: Int)

    private fun readFrameInfo(meta: IIOMetadata): FrameInfo {
        var left = 0
        var top = 0
        var disposal = "none"
        var delayMs = 100
        try {
            val tree = meta.getAsTree(meta.nativeMetadataFormatName) as Element
            (tree.getElementsByTagName("ImageDescriptor").item(0) as? Element)?.let {
                left = it.getAttribute("imageLeftPosition").toIntOrNull() ?: 0
                top = it.getAttribute("imageTopPosition").toIntOrNull() ?: 0
            }
            (tree.getElementsByTagName("GraphicControlExtension").item(0) as? Element)?.let {
                disposal = it.getAttribute("disposalMethod").ifEmpty { "none" }
                val delay = it.getAttribute("delayTime").toIntOrNull() ?: 10
                delayMs = (delay * 10).coerceAtLeast(20)
            }
        } catch (_: Exception) {
        }
        return FrameInfo(left, top, disposal, delayMs)
    }

    /** GIF 的逻辑画布尺寸（Logical Screen Descriptor）；读不到时返回 null，调用方退回首帧尺寸。 */
    private fun readLogicalScreenSize(reader: ImageReader): Pair<Int, Int>? = try {
        val meta = reader.streamMetadata
        val tree = meta?.getAsTree(meta.nativeMetadataFormatName) as? Element
        val lsd = tree?.getElementsByTagName("LogicalScreenDescriptor")?.item(0) as? Element
        val w = lsd?.getAttribute("logicalScreenWidth")?.toIntOrNull() ?: 0
        val h = lsd?.getAttribute("logicalScreenHeight")?.toIntOrNull() ?: 0
        if (w > 0 && h > 0) w to h else null
    } catch (_: Exception) {
        null
    }

    private fun copyOf(img: BufferedImage): BufferedImage {
        val copy = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
        val g = copy.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.dispose()
        return copy
    }

    /** 读取 GIF 的 NETSCAPE 扩展循环次数（无则 0 = 无限）。 */
    private fun readLoopCount(reader: ImageReader): Int = try {
        val meta = reader.getStreamMetadata() ?: return 0
        val tree = meta.getAsTree(meta.nativeMetadataFormatName) as Element
        val nodes = tree.getElementsByTagName("NetScapeLoopCountExtension")
        if (nodes.length > 0) {
            (nodes.item(0) as Element).getAttribute("loopCount").toIntOrNull() ?: 0
        } else {
            0
        }
    } catch (_: Exception) {
        0
    }

    /**
     * BufferedImage → 目标尺寸的 Compose ImageBitmap。
     *
     * AWT 的 TYPE_INT_ARGB 是「ARGB 打包 int」，在小端机器上它的字节序列正好是 B,G,R,A，
     * 因此可以直接按 BGRA_8888 交给 Skia，省掉原先逐像素移位拷贝的百万次循环。
     */
    private fun bufferedImageToImageBitmap(img: BufferedImage, targetW: Int, targetH: Int): ImageBitmap {
        val iw = img.width
        val ih = img.height
        if (iw == 0 || ih == 0) {
            return emptyBitmap(1, 1)
        }

        val scaled = if (targetW != iw || targetH != ih) {
            val s = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB)
            val g = s.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(img, 0, 0, targetW, targetH, null)
            g.dispose()
            s
        } else {
            img
        }

        val argb = scaled.getRGB(0, 0, targetW, targetH, null, 0, targetW)
        val buffer = java.nio.ByteBuffer.allocate(targetW * targetH * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.asIntBuffer().put(argb)
        val info = ImageInfo(targetW, targetH, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
        val image = Image.makeRaster(info, buffer.array(), info.minRowBytes)
        return image.toComposeImageBitmap()
    }

    private fun emptyBitmap(w: Int, h: Int): ImageBitmap {
        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        return Image.makeRaster(info, ByteArray(w * h * 4), info.minRowBytes).toComposeImageBitmap()
    }
}
