package gallery

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader
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
     * 解码动画 GIF 为帧列表。[maxDim] 限制单帧最大边长（大图按目标尺寸缩小，
     * 避免全尺寸加载过多内存）。帧直接用像素拷贝转成 Skia ImageBitmap（不经 PNG 中转）。
     */
    fun decode(bytes: ByteArray, maxDim: Int): GifAnimation? {
        return runCatching {
            ByteArrayInputStream(bytes).use { bis ->
                val imageInputStream: ImageInputStream? = ImageIO.createImageInputStream(bis)
                val reader = ImageIO.getImageReadersByFormatName("gif").next()
                if (imageInputStream != null) reader.input = imageInputStream

                val loopCount = readLoopCount(reader)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                val numFrames = reader.getNumImages(true)

                // 单帧尺寸同时受 maxDim 与「总像素预算 / 帧数」两个约束。
                val limitScale = minOf(1f, maxDim.toFloat() / maxOf(width, height).toFloat())
                val perFrameBudget = MAX_TOTAL_PIXELS.toDouble() / numFrames.coerceAtLeast(1)
                val budgetScale = sqrt(perFrameBudget / (width.toDouble() * height.toDouble())).toFloat()
                val scale = minOf(limitScale, budgetScale).coerceIn(0.01f, 1f)
                val frameW = maxOf(1, (width * scale).toInt())
                val frameH = maxOf(1, (height * scale).toInt())

                val frames = ArrayList<ImageBitmap>(numFrames)
                val delays = ArrayList<Int>(numFrames)
                for (i in 0 until numFrames) {
                    val image = reader.read(i)
                    frames.add(bufferedImageToImageBitmap(image, frameW, frameH))
                    delays.add(getDelay(reader.getImageMetadata(i)))
                }

                reader.dispose()
                imageInputStream?.close()

                GifAnimation(frames, delays, loopCount, width, height)
            }
        }.getOrNull()
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

    private fun getDelay(meta: javax.imageio.metadata.IIOMetadata): Int = try {
        val tree = meta.getAsTree(meta.nativeMetadataFormatName) as Element
        val nodes = tree.getElementsByTagName("GraphicControlExtension")
        if (nodes.length > 0) {
            val delay = (nodes.item(0) as Element).getAttribute("delayTime").toIntOrNull() ?: 10
            (delay * 10).coerceAtLeast(20)
        } else {
            100
        }
    } catch (_: Exception) {
        100
    }
}
