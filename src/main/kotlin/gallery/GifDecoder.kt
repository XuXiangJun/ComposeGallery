package gallery

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream

data class GifAnimation(
    val frames: List<ImageBitmap>,
    val delaysMs: List<Int>,
    val loopCount: Int,
    val width: Int,
    val height: Int,
)

object GifDecoder {
    fun decode(bytes: ByteArray): GifAnimation? {
        return runCatching {
            ByteArrayInputStream(bytes).use { bis ->
                val imageInputStream = ImageIO.createImageInputStream(bis)
                val readers = ImageIO.getImageReadersByFormatName("gif")
                val reader = readers.next()
                reader.input = imageInputStream

                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                val numFrames = reader.getNumImages(true)

                val frames = mutableListOf<ImageBitmap>()
                val delays = mutableListOf<Int>()

                for (i in 0 until numFrames) {
                    val image = reader.read(i)
                    ByteArrayOutputStream().use { baos ->
                        ImageIO.write(image, "png", baos)
                        val skiaImage = Image.makeFromEncoded(baos.toByteArray())
                        frames.add(skiaImage.toComposeImageBitmap())
                        skiaImage.close()
                    }

                    val delay = getDelay(reader.getImageMetadata(i))
                    delays.add(delay)
                }

                reader.dispose()
                imageInputStream.close()

                GifAnimation(frames, delays, loopCount = 0, width, height)
            }
        }.getOrNull()
    }

    private fun getDelay(meta: javax.imageio.metadata.IIOMetadata): Int {
        return try {
            val tree = meta.getAsTree(meta.nativeMetadataFormatName) as org.w3c.dom.Element
            val nodes = tree.getElementsByTagName("GraphicControlExtension")
            if (nodes.length > 0) {
                val node = nodes.item(0) as org.w3c.dom.Element
                val delay = node.getAttribute("delayTime").toIntOrNull() ?: 10
                (delay * 10).coerceAtLeast(20)
            } else {
                100
            }
        } catch (e: Exception) {
            100
        }
    }
}
