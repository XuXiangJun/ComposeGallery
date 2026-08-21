package gallery

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 无头测试：验证图片解码（磁盘文件 / 压缩包）、动画 GIF 解码缩放与 loopCount，
 * 以及书架数据的 JSON 持久化序列化。
 */
class GalleryCoreTest {

    @TempDir
    lateinit var tmpDir: File

    @Test
    fun fileSourceDecodesAndScales() = runBlocking {
        val png = File(tmpDir, "solid.png")
        writeSolidPng(png, 400, 300, Color.RED)

        val fs = FileSource(png)
        val thumb = ImageLoader.loadThumbnail(fs, 100)
        assertNotNull(thumb)
        assertEquals(100, thumb.width)
        assertEquals(75, thumb.height)
        assertPixel(thumb, 0xFFFF0000.toInt(), "file thumbnail")

        val full = ImageLoader.loadFull(fs)
        assertNotNull(full)
        assertEquals(400, full.width)
        assertEquals(300, full.height)
    }

    @Test
    fun zipArchiveSourceDecodes() = runBlocking {
        val png = File(tmpDir, "solid.png")
        writeSolidPng(png, 400, 300, Color.RED)
        val zipFile = File(tmpDir, "solid.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("photos/red.png"))
            zos.write(png.readBytes())
            zos.closeEntry()
        }

        val reader = ArchiveReader.open(zipFile)
        val e = reader.entries.firstOrNull { it.name == "photos/red.png" }
        assertNotNull(e) { "zip entry missing; entries=${reader.entries.map { it.name }}" }
        val aSrc = ArchiveSource(zipFile, e.name, reader, e.size, e.modified)
        val aThumb = ImageLoader.loadThumbnail(aSrc, 100)
        assertNotNull(aThumb)
        assertEquals(100, aThumb.width)
        assertPixel(aThumb, 0xFFFF0000.toInt(), "archive thumbnail")
        val aFull = ImageLoader.loadFull(aSrc)
        assertNotNull(aFull)
        assertEquals(400, aFull.width)
        reader.close()
    }

    @Test
    fun scaledBitmapSurvivesGc() = runBlocking {
        val png = File(tmpDir, "solid.png")
        writeSolidPng(png, 400, 300, Color.RED)
        val thumb = ImageLoader.loadThumbnail(FileSource(png), 100)
        assertNotNull(thumb)
        System.gc()
        Thread.sleep(200)
        assertPixel(thumb, 0xFFFF0000.toInt(), "after GC")
    }

    @Test
    fun bookshelfGsonRoundtrip() {
        val gson = GsonBuilder().create()
        val book = BookEntry("id1", "测试漫画", "archive", "C:/a.zip", "C:/a.zip!/001.jpg", 12, 57, 123456L)
        val json = gson.toJson(listOf(book))
        val back = gson.fromJson<List<BookEntry>>(json, object : TypeToken<List<BookEntry>>() {}.type)
        assertEquals(1, back.size)
        assertEquals(book, back[0])
    }

    @Test
    fun animatedGifDecodesWithScalingAndLoopcount() {
        val gifFile = File(tmpDir, "anim.gif")
        makeAnimatedGif(gifFile)
        val anim = GifDecoder.decode(gifFile.readBytes(), maxDim = 20)

        assertNotNull(anim)
        assertEquals(3, anim.frames.size)
        assertEquals(listOf(100, 100, 100), anim.delaysMs)
        assertEquals(40, anim.width)
        assertEquals(40, anim.height)
        assertEquals(20, anim.frames[0].width)
        assertEquals(20, anim.frames[0].height)
        assertPixel(anim.frames[0], 0xFFFF0000.toInt(), "gif frame 0")
        assertPixel(anim.frames[1], 0xFF00FF00.toInt(), "gif frame 1")
        assertPixel(anim.frames[2], 0xFF0000FF.toInt(), "gif frame 2")
        assertEquals(0, anim.loopCount)
    }

    // ---------- helpers ----------

    private fun writeSolidPng(file: File, w: Int, h: Int, color: Color) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, w, h)
        g.dispose()
        ImageIO.write(img, "png", file)
    }

    private fun assertPixel(bmp: ImageBitmap, expected: Int, label: String) {
        val pm = bmp.toPixelMap()
        val c = pm.buffer[10 * pm.stride + 10]
        val er = (expected shr 16) and 0xFF
        val eg = (expected shr 8) and 0xFF
        val eb = expected and 0xFF
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        assertTrue(
            kotlin.math.abs(r - er) <= 8 && kotlin.math.abs(g - eg) <= 8 && kotlin.math.abs(b - eb) <= 8,
            "$label pixel mismatch: got r=$r g=$g b=$b, expected r=$er g=$eg b=$eb",
        )
    }

    private fun makeAnimatedGif(file: File) {
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        val ios = ImageIO.createImageOutputStream(file)
        writer.output = ios
        val param = writer.defaultWriteParam
        val type = javax.imageio.ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB)
        val (w, h) = 40 to 40

        fun frame(color: Int): BufferedImage {
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g2 = img.createGraphics()
            g2.color = Color(color)
            g2.fillRect(0, 0, w, h)
            g2.dispose()
            return img
        }

        fun meta(): javax.imageio.metadata.IIOMetadata {
            val m = writer.getDefaultImageMetadata(type, param)
            val root = m.getAsTree(m.nativeMetadataFormatName) as org.w3c.dom.Element
            val gce = root.getElementsByTagName("GraphicControlExtension").item(0) as org.w3c.dom.Element
            gce.setAttribute("delayTime", "10")
            gce.setAttribute("disposalMethod", "none")
            m.setFromTree(m.nativeMetadataFormatName, root)
            return m
        }

        writer.prepareWriteSequence(null)
        writer.writeToSequence(javax.imageio.IIOImage(frame(0xFFFF0000.toInt()), null, meta()), param)
        writer.writeToSequence(javax.imageio.IIOImage(frame(0xFF00FF00.toInt()), null, meta()), param)
        writer.writeToSequence(javax.imageio.IIOImage(frame(0xFF0000FF.toInt()), null, meta()), param)
        writer.endWriteSequence()
        writer.dispose()
        ios.close()
    }
}
