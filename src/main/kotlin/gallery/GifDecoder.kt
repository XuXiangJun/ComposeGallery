package gallery

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream
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

                val frames = ArrayList<ImageBitmap>(numFrames)
                val delays = ArrayList<Int>(numFrames)
                for (i in 0 until numFrames) {
                    val image = reader.read(i)
                    frames.add(bufferedImageToImageBitmap(image, maxDim))
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

    /** BufferedImage → 目标尺寸的 Compose ImageBitmap（ARGB → RGBA → makeRaster 拷贝）。 */
    private fun bufferedImageToImageBitmap(img: BufferedImage, maxDim: Int): ImageBitmap {
        val iw = img.width
        val ih = img.height
        if (iw == 0 || ih == 0) {
            return emptyBitmap(1, 1)
        }
        val scale = minOf(1f, maxDim.toFloat() / maxOf(iw, ih).toFloat())
        val w = maxOf(1, (iw * scale).toInt())
        val h = maxOf(1, (ih * scale).toInt())

        val scaled = if (w != iw || h != ih) {
            val s = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = s.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(img, 0, 0, w, h, null)
            g.dispose()
            s
        } else {
            img
        }

        val argb = scaled.getRGB(0, 0, w, h, null, 0, w)
        val pixels = ByteArray(w * h * 4)
        for (i in argb.indices) {
            val c = argb[i]
            val idx = i * 4
            pixels[idx] = ((c ushr 16) and 0xFF).toByte()   // R
            pixels[idx + 1] = ((c ushr 8) and 0xFF).toByte()  // G
            pixels[idx + 2] = (c and 0xFF).toByte()          // B
            pixels[idx + 3] = ((c ushr 24) and 0xFF).toByte() // A
        }
        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val image = Image.makeRaster(info, pixels, info.minRowBytes)
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
