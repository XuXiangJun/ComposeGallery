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
import gallery.ui.ZoomState
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

    private fun item(name: String) = ImageItem(FileSource(File(tmpDir, name)), name, 1024L, 0L)

    /**
     * 滚轮/手势缩放时，光标下的内容应保持不动。
     * 注意要选一个不触及视口边界的场景：一旦图片边缘已经贴住视口，
     * 锚点必然会被边界限制破坏（那是刻意的，见 zoomClampsImageInsideViewport）。
     */
    @Test
    fun zoomAtKeepsFocalPointStable() {
        val zoom = ZoomState()
        zoom.viewportW = 400f
        zoom.viewportH = 300f
        zoom.baseW = 400f // 相当于 400x300 的图、fit = 1（正好铺满视口）
        zoom.baseH = 300f

        val focal = androidx.compose.ui.geometry.Offset(150f, 120f)
        val before = zoom.imagePointAt(focal)
        repeat(3) { zoom.zoomAt(1.2f, focal) }
        val after = zoom.imagePointAt(focal)

        assertTrue(zoom.scale > 1f, "应当确实放大了")
        assertEquals(before.x, after.x, 0.01f, "光标下的图片横向坐标应保持不动")
        assertEquals(before.y, after.y, 0.01f, "光标下的图片纵向坐标应保持不动")
    }

    /**
     * 放大到超过视口后，图片边缘不能被推出视口 ——
     * 这正是「鼠标在界面两边滚动，图片就往两边跑」的成因。
     */
    @Test
    fun zoomClampsImageInsideViewport() {
        val zoom = ZoomState()
        zoom.viewportW = 400f
        zoom.viewportH = 300f
        zoom.baseW = 400f
        zoom.baseH = 300f

        // 故意拿左上角当锚点反复放大：若没有边界限制，图片会被一路推出去、飞出视口
        repeat(20) { zoom.zoomAt(1.5f, androidx.compose.ui.geometry.Offset(0f, 0f)) }

        val drawnW = zoom.baseW * zoom.scale
        val drawnH = zoom.baseH * zoom.scale
        val left = (zoom.viewportW - drawnW) / 2f + zoom.offsetX
        val top = (zoom.viewportH - drawnH) / 2f + zoom.offsetY

        assertTrue(drawnW > zoom.viewportW, "前提：图片应已放大到超过视口")
        assertTrue(left <= 0.01f, "图片左边缘不应越过视口左边界（否则露出黑边）")
        assertTrue(top <= 0.01f, "图片上边缘不应越过视口上边界")
        assertTrue(left + drawnW >= zoom.viewportW - 0.01f, "图片右边缘不应离开视口右边界")
        assertTrue(top + drawnH >= zoom.viewportH - 0.01f, "图片下边缘不应离开视口下边界")
    }

    /** 一路缩回适应窗口及以下时应回到居中状态，不留平移残量。 */
    @Test
    fun zoomAtShrinkReturnsToCenter() {
        val zoom = ZoomState()
        zoom.viewportW = 800f
        zoom.viewportH = 600f
        zoom.baseW = 400f
        zoom.baseH = 300f

        val focal = androidx.compose.ui.geometry.Offset(700f, 520f)
        repeat(4) { zoom.zoomAt(1.15f, focal) }
        repeat(12) { zoom.zoomAt(1f / 1.15f, focal) }

        assertTrue(zoom.scale <= 1f, "应已缩到适应窗口及以下")
        assertEquals(0f, zoom.offsetX, "缩回适应窗口后横向偏移应清零")
        assertEquals(0f, zoom.offsetY, "缩回适应窗口后纵向偏移应清零")
    }

    /** 布局尚未发生（视口/基础尺寸为 0）时，缩放事件不应改动任何状态。 */
    @Test
    fun zoomAtIgnoresEventsBeforeLayout() {
        val zoom = ZoomState()
        zoom.zoomAt(1.15f, androidx.compose.ui.geometry.Offset(10f, 10f))
        assertEquals(1f, zoom.scale)
        assertEquals(0f, zoom.offsetX)
        assertEquals(0f, zoom.offsetY)
    }

    /**
     * 造一棵多层目录树（相对目录 -> 文件名），用来验证并行扫描的结果与串行语义一致。
     * 用 List 而不是 Map：顶层目录会出现多次，Map 会覆盖同名 key。
     */
    private fun makeTree(root: File, files: List<Pair<String, String>>): Set<String> {
        files.forEach { (rel, name) ->
            val dir = if (rel.isEmpty()) root else File(root, rel)
            dir.mkdirs()
            File(dir, name).writeBytes(byteArrayOf(0))
        }
        // 只有图片扩展名计入期望值：扫描会（正确地）忽略 not-image.txt 这类文件。
        return files.map { (_, name) -> name }
            .filter { ext -> ext.substringAfterLast('.', "").lowercase() in ImageScanner.EXTENSIONS }
            .toSet()
    }

    @Test
    fun recursiveScanFindsImagesInNestedFolders() = runBlocking {
        val root = File(tmpDir, "tree")
        val expected = makeTree(
            root,
            listOf(
                "" to "a.jpg",
                "" to "b.png",
                "" to "not-image.txt",
                "a" to "c.gif",
                "a/sub" to "d.webp",
                "b" to "e.jpeg",
            ),
        )

        // 非递归：只看顶层，且扩展名不符的忽略
        val flat = ImageScanner.scan(root, recursive = false)
        assertEquals(setOf("a.jpg", "b.png"), flat.map { it.name }.toSet())

        // 递归：所有层级的图片都要找到（并行遍历不能漏目录、也不能重复计数）
        val all = ImageScanner.scan(root, recursive = true)
        assertEquals(expected, all.map { it.name }.toSet())
        assertEquals(expected.size, all.size, "不应有重复或遗漏")
    }

    @Test
    fun recursiveScanRespectsMaxFiles() = runBlocking {
        val root = File(tmpDir, "many").apply { mkdirs() }
        File(root, "sub").apply { mkdirs() }
        repeat(20) { i -> File(root, "img$i.jpg").writeBytes(byteArrayOf(0)) }
        repeat(20) { i -> File(File(root, "sub"), "nested$i.png").writeBytes(byteArrayOf(0)) }

        val limited = ImageScanner.scan(root, recursive = true, maxFiles = 10)
        assertEquals(10, limited.size)
    }

    @Test
    fun filterImagesTrimsAndIgnoresCase() {
        val list = listOf(item("Cat.jpg"), item("dog.png"), item("catalog.gif"))
        assertEquals(list, filterImages(list, ""))
        assertEquals(list, filterImages(list, "   "), "纯空白查询应视为无过滤")
        assertEquals(listOf(list[0], list[2]), filterImages(list, "CAT"))
        assertEquals(emptyList<ImageItem>(), filterImages(list, "zzz"))
    }

    @Test
    fun stepSkipsFilteredOutImages() {
        val state = AppState()
        state.images = listOf(item("Cat.jpg"), item("dog.png"), item("catalog.gif"))

        // 无搜索：在整个列表里循环（step 是纯函数，不修改 selectedIndex）
        state.selectedIndex = 2
        assertEquals(0, state.step(1), "末尾回绕到第一张")
        assertEquals(1, state.step(-1), "往前一张")

        // 有搜索：只在可见项（索引 0 与 2）之间移动，跳过被过滤掉的 dog.png
        state.searchQuery = "c"
        state.selectedIndex = 0
        assertEquals(2, state.step(1), "跳过被过滤掉的 dog.png")
        assertEquals(0, state.firstVisibleIndex())

        state.selectedIndex = 2
        assertEquals(0, state.step(1), "末尾回绕到第一张可见项")
        assertEquals(0, state.step(-1), "从第一张可见项往前回到末尾可见项")
    }

    @Test
    fun stepKeepsPositionWhenOnlyOneVisible() {
        val state = AppState()
        state.images = listOf(item("a.jpg"), item("b.png"))
        state.searchQuery = "a"
        state.selectedIndex = 0
        assertEquals(0, state.step(1))
        assertEquals(0, state.step(-1))
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
