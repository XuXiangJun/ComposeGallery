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
import kotlinx.coroutines.runBlocking

/**
 * 无头自测：验证 ImageLoader 对「磁盘文件」与「zip 压缩包内条目」两条路径的
 * 解码、缩放与内存安全（缩放后 GC 不悬空）。
 */
fun main() = runBlocking {
    val tmp = System.getProperty("java.io.tmpdir")
    val png = File(tmp, "gallery-selftest.png")

    // 生成 400x300 纯红测试图
    val img = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = Color.RED
    g.fillRect(0, 0, 400, 300)
    g.dispose()
    ImageIO.write(img, "png", png)
    println("[selftest] png=${png.length()} bytes")

    // 1) 文件来源：缩略图 + 全图
    val fs = FileSource(png)
    val thumb = ImageLoader.loadThumbnail(fs, 100)
    check(thumb != null && thumb.width == 100 && thumb.height == 75) {
        "file thumbnail dims ${thumb?.width}x${thumb?.height}"
    }
    checkRed(thumb, "file thumbnail")
    val full = ImageLoader.loadFull(fs)
    check(full != null && full.width == 400 && full.height == 300) { "file full dims" }
    println("[selftest] file source OK (thumb ${thumb.width}x${thumb.height}, full ${full.width}x${full.height})")

    // 2) zip 来源：把图打包进 zip，再从条目读取解码
    val zipFile = File(tmp, "gallery-selftest.zip")
    ZipOutputStream(zipFile.outputStream()).use { zos ->
        zos.putNextEntry(ZipEntry("photos/red.png"))
        zos.write(png.readBytes())
        zos.closeEntry()
    }
    val reader = ArchiveReader.open(zipFile)
    val e = reader.entries.firstOrNull { it.name == "photos/red.png" }
    check(e != null) { "zip entry missing; entries=${reader.entries.map { it.name }}" }
    val aSrc = ArchiveSource(zipFile, e.name, reader, e.size, e.modified)
    val aThumb = ImageLoader.loadThumbnail(aSrc, 100)
    check(aThumb != null && aThumb.width == 100 && aThumb.height == 75) {
        "archive thumbnail dims ${aThumb?.width}x${aThumb?.height}"
    }
    checkRed(aThumb, "archive thumbnail")
    val aFull = ImageLoader.loadFull(aSrc)
    check(aFull != null && aFull.width == 400 && aFull.height == 300) { "archive full dims" }
    reader.close()
    println("[selftest] zip archive source OK (thumb ${aThumb.width}x${aThumb.height}, full ${aFull.width}x${aFull.height})")

    // 3) 缩放结果 GC 后不悬空
    System.gc()
    Thread.sleep(300)
    checkRed(thumb, "file thumbnail after GC")
    println("[selftest] after-GC OK")

    // 4) 书架数据 Gson 往返
    val gson = GsonBuilder().create()
    val book = BookEntry("id1", "测试漫画", "archive", "C:/a.zip", "C:/a.zip!/001.jpg", 12, 57, 123456L)
    val json = gson.toJson(listOf(book))
    val back = gson.fromJson<List<BookEntry>>(json, object : TypeToken<List<BookEntry>>() {}.type)
    check(back.size == 1 && back[0] == book) { "bookshelf gson roundtrip failed" }
    println("[selftest] bookshelf gson roundtrip OK")

    png.delete()
    zipFile.delete()
    println("SELF TEST PASSED")
}

private fun checkRed(bmp: ImageBitmap, label: String) {
    val pm = bmp.toPixelMap()
    val c = pm.buffer[20 * pm.stride + 20]
    val r = (c shr 16) and 0xFF
    val gg = (c shr 8) and 0xFF
    val b = c and 0xFF
    check(r > 200 && gg < 60 && b < 60) { "$label pixel not red: r=$r g=$gg b=$b" }
}
