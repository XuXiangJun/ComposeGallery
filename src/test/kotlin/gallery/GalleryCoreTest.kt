package gallery

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import gallery.ui.ZoomState
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 无头测试：验证图片解码（磁盘文件 / 压缩包）、动画 GIF 解码缩放与 loopCount，
 * 以及书架数据的 JSON 持久化序列化。
 */
class GalleryCoreTest {

    companion object {
        /**
         * 把两个 Store 的数据目录隔离到临时目录。
         *
         * 目录由 `-Dcompose.gallery.home` 决定，而 Kotlin `object` 只在首次访问时初始化 ——
         * companion 的 init 随类加载完成、早于任何测试方法，所以属性一定先于
         * BookshelfStore / SettingsStore 被设上。否则构造 [AppState] 的测试会去读写
         * 真实的 ~/.ComposeGallery，那既是副作用也会让结果依赖机器状态。
         */
        private val isolatedHome: File = Files.createTempDirectory("gallery-test-home").toFile().apply {
            System.setProperty("compose.gallery.home", absolutePath)
        }

        @AfterAll
        @JvmStatic
        fun deleteIsolatedHome() {
            isolatedHome.deleteRecursively()
        }
    }

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

    /** 缩略图落盘后，清空内存缓存再取应命中磁盘缓存（即使原图已经读不到）。 */
    @Test
    fun thumbnailsArePersistedToDisk() = runBlocking {
        val png = File(tmpDir, "disk.png")
        writeSolidPng(png, 300, 300, Color.BLUE)
        val source = FileSource(png)
        assertNotNull(ImageLoader.loadThumbnail(source, 64))
        assertTrue(DiskThumbnailCache.dir.listFiles().orEmpty().any { it.name.endsWith(".webp") })

        ImageLoader.invalidateCache()
        // 同一个 cacheKey（路径 + 修改时间 + 大小在构造时已固定），但文件内容读不到了
        png.delete()
        val again = ImageLoader.loadThumbnail(source, 64)
        assertNotNull(again, "应从磁盘缓存解出缩略图")
        assertPixel(again, 0xFF0000FF.toInt(), "disk-cached thumbnail")
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

    // ---------- 书架进度：切图集时不能冲掉别的条目 ----------

    /**
     * 回归测试：打开新图集前必须先存**上一个**图集的进度。
     *
     * 曾经的写法是在 `state.folder = dir` 之后才调 `closeViewer()` 存进度，于是
     * updateProgress 拿到「新图集的 id + 上一次的 selectedIndex」，把书架上另一个条目的
     * progress/total 一起覆盖掉。这里按 App.loadFolder 的顺序复刻一遍，并重开 AppState
     * 从磁盘读回，确认落盘内容是对的。
     */
    @Test
    fun openingAnotherLocationDoesNotClobberItsSavedProgress() {
        val a = File(tmpDir, "A").apply { mkdirs() }
        val b = File(tmpDir, "B").apply { mkdirs() }
        repeat(10) { File(a, "a$it.jpg").writeBytes(byteArrayOf(0)) }
        repeat(5) { File(b, "b$it.jpg").writeBytes(byteArrayOf(0)) }
        val aItems = a.listFiles()!!.sortedBy { it.name }.map(::itemFromFile)
        val bItems = b.listFiles()!!.sortedBy { it.name }.map(::itemFromFile)

        // A、B 都在书架上：A 在第 1 张，B 在第 2 张
        val state = AppState()
        state.books = listOf(
            BookEntry(
                id = a.absolutePath, name = "A", type = "folder", path = a.absolutePath,
                cover = aItems.first().file!!.absolutePath, progress = 0, total = 10, lastRead = 1L,
            ),
            BookEntry(
                id = b.absolutePath, name = "B", type = "folder", path = b.absolutePath,
                cover = bItems.first().file!!.absolutePath, progress = 1, total = 5, lastRead = 1L,
            ),
        )

        // 正在看 A，停在第 8 张
        state.folder = a
        state.images = aItems
        state.selectedIndex = 7

        // 复刻 App.loadFolder()：先存旧图集进度，再切位置
        state.saveCurrentProgress()
        state.folder = b
        state.images = bItems
        state.selectedIndex = -1

        // 重开一个 AppState 从磁盘读回
        val reloaded = AppState()
        assertEquals(7, reloaded.progressFor(a.absolutePath), "A 的阅读位置应当被保存")
        assertEquals(1, reloaded.progressFor(b.absolutePath), "B 的进度不应被 A 的阅读位置覆盖")
        assertEquals(5, reloaded.books.single { it.id == b.absolutePath }.total, "B 的总数不应被 A 的图片数覆盖")
    }

    /** 同一个图集重复存同一进度不应产生多余写盘（updateProgress 的短路分支）。 */
    @Test
    fun savingTheSameProgressTwiceIsANoop() {
        val f = File(tmpDir, "C").apply { mkdirs() }
        repeat(3) { File(f, "c$it.jpg").writeBytes(byteArrayOf(0)) }
        val items = f.listFiles()!!.sortedBy { it.name }.map(::itemFromFile)

        val state = AppState()
        state.books = listOf(
            BookEntry(f.absolutePath, "C", "folder", f.absolutePath, items.first().file!!.absolutePath, 2, 3, 1L)
        )
        state.folder = f
        state.images = items
        state.selectedIndex = 2

        state.saveCurrentProgress()
        val afterFirst = state.books.single()
        state.saveCurrentProgress()
        val afterSecond = state.books.single()

        assertEquals(2, afterFirst.progress)
        assertEquals(afterFirst.lastRead, afterSecond.lastRead, "内容没变时不应刷新 lastRead（即没有重复写盘）")
    }

    /** 换了排序方向后，恢复进度应当回到同一张图，而不是同一个下标。 */
    @Test
    fun resumeFollowsImageAcrossSortChanges() {
        val f = File(tmpDir, "D").apply { mkdirs() }
        repeat(5) { File(f, "d$it.jpg").writeBytes(byteArrayOf(0)) }
        val asc = f.listFiles()!!.sortedBy { it.name }.map(::itemFromFile)

        val state = AppState()
        state.books = listOf(
            BookEntry(f.absolutePath, "D", "folder", f.absolutePath, asc.first().file!!.absolutePath, 0, 5, 1L)
        )
        state.folder = f
        state.images = asc
        state.selectedIndex = 1 // d1.jpg
        state.saveCurrentProgress()

        val desc = asc.reversed()
        val idx = AppState().resumeIndexFor(f.absolutePath, desc)
        assertEquals("d1.jpg", desc[idx].name)
        // 旧数据没有 progressKey：退回下标
        state.books = listOf(state.books.single().copy(progressKey = null))
        assertEquals(1, state.resumeIndexFor(f.absolutePath, desc))
    }

    // ---------- 持久化：损坏文件容错 + 原子写不留残留 ----------

    @Test
    fun bookshelfStoreRoundTripsAndLeavesNoTempFiles() {
        val books = listOf(
            BookEntry("id1", "漫画", "archive", "C:/a.zip", "C:/a.zip!/001.jpg", 12, 57, 123456L),
            BookEntry("id2", "文件夹", "folder", "D:/pics", "D:/pics/0001.jpg", 0, 3, 654321L),
        )
        BookshelfStore.save(books)
        // 覆盖写（目标文件已存在）也必须成功
        BookshelfStore.save(books)

        assertEquals(books, BookshelfStore.load(), "原子写 + 覆盖写后应能原样读回")
        val leftovers = BookshelfStore.dir.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray()
        assertTrue(leftovers.isEmpty(), "move 成功后不应残留临时文件，实际: ${leftovers.map { it.name }}")
    }

    @Test
    fun storesFallBackWhenJsonIsCorrupt() {
        BookshelfStore.dir.mkdirs()
        BookshelfStore.file.writeText("{ 这不是合法 JSON")
        SettingsStore.dir.mkdirs()
        SettingsStore.file.writeText("[[[not json")

        assertTrue(BookshelfStore.load().isEmpty(), "书架文件损坏时应退化成空书架而不是抛异常")
        assertEquals(Settings(), SettingsStore.load(), "设置文件损坏时应退回默认值")
    }

    @Test
    fun corruptStoreIsBackedUpAndNullFieldsAreSanitized() {
        BookshelfStore.dir.listFiles { f -> f.name.contains(".corrupt-") }?.forEach { it.delete() }
        BookshelfStore.file.writeText("{ broken")
        BookshelfStore.load()
        val backups = BookshelfStore.dir.listFiles { f -> f.name.startsWith("bookshelf.json.corrupt-") }.orEmpty()
        assertTrue(backups.isNotEmpty(), "损坏的书架文件应被改名备份，而不是等着被下一次 save 覆盖")

        // 缺字段 / 非法枚举：Gson 会填 null，加载后必须已被兜底
        SettingsStore.file.writeText("""{"themeMode":"PURPLE","sortDirection":null,"recent":[null,{"path":"p"}]}""")
        val s = SettingsStore.load()
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertEquals(SortDirection.ASC, s.sortDirection)
        assertTrue(s.recent.isEmpty())

        BookshelfStore.file.writeText("""[{"id":"x","name":"n","type":"folder","path":"x","cover":"c","progress":1,"total":2,"lastRead":3},{"id":"y"}]""")
        assertEquals(listOf("x"), BookshelfStore.load().map { it.id })
    }

    @Test
    fun storeHomeDirIsOverridableForTests() {
        assertEquals(File(System.getProperty("compose.gallery.home")), BookshelfStore.dir)
        assertEquals(BookshelfStore.dir, SettingsStore.dir)
        assertEquals(File(BookshelfStore.dir, "bookshelf.json"), BookshelfStore.file)
    }

    // ---------- 压缩包：GBK 条目名回退 ----------

    /**
     * zip 条目名不是 UTF-8 时（GBK / Shift-JIS 是中文、日文压缩包的常见情况），
     * Commons Compress 按 UTF-8 解出来是乱码；[ArchiveReader] 靠 U+FFFD 探测并回退 GB18030。
     * 这是 README 主打的能力，原先没有测试覆盖。
     */
    @Test
    fun gbkEncodedZipEntryNamesAreRecovered() = runBlocking {
        val png = File(tmpDir, "solid.png")
        writeSolidPng(png, 400, 300, Color.RED)
        val zipFile = File(tmpDir, "gbk.zip")
        // ZipOutputStream 传非 UTF-8 charset 时不置 EFS 标志位，条目名按该 charset 裸写 ——
        // 正是真实世界里那种「Windows 资源管理器压出来的中文 zip」。
        ZipOutputStream(zipFile.outputStream(), Charset.forName("GBK")).use { zos ->
            zos.putNextEntry(ZipEntry("漫画/第一话/001.jpg"))
            zos.write(png.readBytes())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("漫画/第一话/002.png"))
            zos.write(png.readBytes())
            zos.closeEntry()
        }

        ArchiveReader.open(zipFile).use { reader ->
            assertEquals(
                listOf("漫画/第一话/001.jpg", "漫画/第一话/002.png"),
                reader.entries.map { it.name }.sorted(),
                "GBK 条目名应被回退解码回来，而不是变成乱码",
            )

            val items = ImageScanner.scanArchive(zipFile, reader)
            assertEquals(2, items.size)
            assertEquals("001.jpg", items.first().name, "显示名应取条目最后一段")
            val thumb = ImageLoader.loadThumbnail(items.first().source, 100)
            assertNotNull(thumb)
            assertEquals(100, thumb.width)
        }
    }

    /** Shift-JIS 条目名（日文压缩包）应按 Shift_JIS 解码，而不是被当成 GB18030 变成乱码。 */
    @Test
    fun shiftJisZipEntryNamesAreRecovered() {
        val zipFile = File(tmpDir, "sjis.zip")
        ZipOutputStream(zipFile.outputStream(), Charset.forName("Shift_JIS")).use { zos ->
            zos.putNextEntry(ZipEntry("まんが/だい1わ/001.jpg"))
            zos.write(byteArrayOf(0))
            zos.closeEntry()
        }
        ArchiveReader.open(zipFile).use { reader ->
            assertEquals(listOf("まんが/だい1わ/001.jpg"), reader.entries.map { it.name })
        }
    }

    @Test
    fun cbzIsOpenedAsZip() {
        val cbz = File(tmpDir, "book.cbz")
        ZipOutputStream(cbz.outputStream(), Charsets.UTF_8).use { zos ->
            zos.putNextEntry(ZipEntry("001.jpg"))
            zos.write(byteArrayOf(0))
            zos.closeEntry()
        }
        assertTrue(ArchiveReader.isSupported(cbz))
        ArchiveReader.open(cbz).use { assertEquals(listOf("001.jpg"), it.entries.map { e -> e.name }) }
    }

    /** 带加密标志的 zip 条目应在打开时就明确报「不支持」，而不是每张图各自解码失败。 */
    @Test
    fun encryptedZipIsRejectedUpFront() {
        val zipFile = File(tmpDir, "enc.zip")
        ZipOutputStream(zipFile.outputStream(), Charsets.UTF_8).use { zos ->
            zos.putNextEntry(ZipEntry("001.jpg"))
            zos.write(byteArrayOf(0))
            zos.closeEntry()
        }
        // 写入端（JDK / commons-compress）都不肯写加密条目，这里直接把 general purpose
        // flag 的 bit 0（encrypted）打到本地头（偏移 6）和中央目录头（偏移 8）上。
        val bytes = zipFile.readBytes()
        for (i in 0 until bytes.size - 4) {
            if (bytes[i] == 'P'.code.toByte() && bytes[i + 1] == 'K'.code.toByte()) {
                when {
                    bytes[i + 2] == 3.toByte() && bytes[i + 3] == 4.toByte() -> bytes[i + 6] = (bytes[i + 6].toInt() or 1).toByte()
                    bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte() -> bytes[i + 8] = (bytes[i + 8].toInt() or 1).toByte()
                }
            }
        }
        zipFile.writeBytes(bytes)
        kotlin.test.assertFailsWith<EncryptedZipException> { ArchiveReader.open(zipFile) }
    }

    /** macOS 打包附带的 __MACOSX/ 与 ._xxx 元数据不能混进图片列表。 */
    @Test
    fun macMetadataEntriesAreSkipped() = runBlocking {
        val zipFile = File(tmpDir, "mac.zip")
        ZipOutputStream(zipFile.outputStream(), Charsets.UTF_8).use { zos ->
            for (name in listOf("book/001.jpg", "__MACOSX/book/._001.jpg", "book/._002.jpg")) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(byteArrayOf(0))
                zos.closeEntry()
            }
        }
        ArchiveReader.open(zipFile).use { reader ->
            assertEquals(listOf("001.jpg"), ImageScanner.scanArchive(zipFile, reader).map { it.name })
        }
    }

    /** UTF-8 zip 不该被回退路径破坏（回退只会多做一次打开，结果必须仍然正确）。 */
    @Test
    fun utf8ZipEntryNamesStillWork() = runBlocking {
        val png = File(tmpDir, "solid.png")
        writeSolidPng(png, 400, 300, Color.RED)
        val zipFile = File(tmpDir, "utf8.zip")
        ZipOutputStream(zipFile.outputStream(), Charsets.UTF_8).use { zos ->
            zos.putNextEntry(ZipEntry("漫画/001.jpg"))
            zos.write(png.readBytes())
            zos.closeEntry()
        }

        ArchiveReader.open(zipFile).use { reader ->
            assertEquals(listOf("漫画/001.jpg"), reader.entries.map { it.name })
            assertEquals(1, ImageScanner.scanArchive(zipFile, reader).size)
        }
    }

    /**
     * 名字里带合法 `?` 的 zip 不能被误判成 GBK。
     *
     * 这是编码探测的镜像用例：commons-compress 把畸形 UTF-8 字节解成 `?`，所以任何
     * 「查 `?` 就当它是 GBK」的启发式都会把这种正常 zip 按 GB18030 重解一遍，变成乱码。
     * 现在的探测走 rawName + 严格 UTF-8 解码，不依赖解码后的字符。
     */
    @Test
    fun zipNamesContainingQuestionMarkStayUtf8() {
        val zipFile = File(tmpDir, "question.zip")
        ZipOutputStream(zipFile.outputStream(), Charsets.UTF_8).use { zos ->
            zos.putNextEntry(ZipEntry("what?.jpg"))
            zos.write(byteArrayOf(1))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("схема/001.png"))
            zos.write(byteArrayOf(1))
            zos.closeEntry()
        }

        ArchiveReader.open(zipFile).use { reader ->
            assertEquals(
                listOf("what?.jpg", "схема/001.png"),
                reader.entries.map { it.name }.sorted(),
                "合法 '?' 与非 ASCII 的 UTF-8 名字都不该触发 GB18030 回退",
            )
        }
    }

    // ---------- 解码失败：契约是返回 null ----------
    /**
     * [ImageLoader.loadFull] 失败时必须返回 null，ImageViewer 靠这个走失败分支。
     * 曾经的 UI 把「还在加载」和「解码失败」表现得一模一样，静态图会永远转圈。
     */
    @Test
    fun undecodableBytesReturnNullInsteadOfThrowing() = runBlocking {
        val notAnImage = File(tmpDir, "not-image.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) }
        // PNG 魔数 + 垃圾内容：能过格式探测、过不了解码
        val truncated = File(tmpDir, "truncated.png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3))
        }

        for (f in listOf(notAnImage, truncated)) {
            assertNull(ImageLoader.loadFull(FileSource(f)), "${f.name} 解码失败应返回 null")
            assertNull(ImageLoader.loadThumbnail(FileSource(f), 100), "${f.name} 缩略图失败应返回 null")
        }
    }

    private fun item(name: String) = ImageItem(FileSource(File(tmpDir, name)), name, 1024L, 0L)

    /** 由磁盘文件构造 ImageItem（目录扫描的等价物，供进度类测试使用）。 */
    private fun itemFromFile(f: File) = ImageItem(FileSource(f), f.name, f.length(), f.lastModified())

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
    fun naturalSortOrdersNumbersByValue() {
        val names = listOf("10.jpg", "2.jpg", "1.jpg", "Page 02.png", "page 1.png", "第10话.jpg", "第9话.jpg", "01.jpg")
        assertEquals(
            listOf("01.jpg", "1.jpg", "2.jpg", "10.jpg", "page 1.png", "Page 02.png", "第9话.jpg", "第10话.jpg"),
            names.sortedWith(NaturalOrder),
        )
        val big = "img99999999999999999999999.jpg"
        assertTrue(naturalCompare("img2.jpg", big) < 0, "超长数字段不能溢出")
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

    /**
     * 差分帧 GIF：第 2 帧只有右下角 10×10 的一小块（偏移 30,30）。
     * 解码器必须把它合成到画布上，而不是把局部帧拉伸成整张图。
     */
    @Test
    fun animatedGifComposesPartialFrames() {
        val gifFile = File(tmpDir, "diff.gif")
        makeDiffGif(gifFile)
        val anim = GifDecoder.decode(gifFile.readBytes(), maxDim = 40)

        assertNotNull(anim)
        assertEquals(2, anim.frames.size)
        assertEquals(40, anim.frames[1].width)
        // (10,10) 不在差分块里：应仍是第 1 帧的红色，而不是被拉伸的绿色
        assertPixel(anim.frames[1], 0xFFFF0000.toInt(), "gif diff frame outside patch")
        val pm = anim.frames[1].toPixelMap()
        val c = pm.buffer[35 * pm.stride + 35]
        assertEquals(0xFF, (c shr 8) and 0xFF, "差分块内应为绿色")
        assertEquals(0x00, (c shr 16) and 0xFF, "差分块内应为绿色")
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

    private fun makeDiffGif(file: File) {
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        val ios = ImageIO.createImageOutputStream(file)
        writer.output = ios
        val param = writer.defaultWriteParam
        val type = javax.imageio.ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB)

        fun solid(w: Int, h: Int, color: Int): BufferedImage {
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g2 = img.createGraphics()
            g2.color = Color(color)
            g2.fillRect(0, 0, w, h)
            g2.dispose()
            return img
        }

        fun meta(left: Int, top: Int, w: Int, h: Int): javax.imageio.metadata.IIOMetadata {
            val m = writer.getDefaultImageMetadata(type, param)
            val root = m.getAsTree(m.nativeMetadataFormatName) as org.w3c.dom.Element
            val gce = root.getElementsByTagName("GraphicControlExtension").item(0) as org.w3c.dom.Element
            gce.setAttribute("delayTime", "10")
            gce.setAttribute("disposalMethod", "doNotDispose")
            val desc = root.getElementsByTagName("ImageDescriptor").item(0) as org.w3c.dom.Element
            desc.setAttribute("imageLeftPosition", left.toString())
            desc.setAttribute("imageTopPosition", top.toString())
            desc.setAttribute("imageWidth", w.toString())
            desc.setAttribute("imageHeight", h.toString())
            m.setFromTree(m.nativeMetadataFormatName, root)
            return m
        }

        // 逻辑画布 40×40：写入器按第一帧的尺寸生成 Logical Screen Descriptor
        writer.prepareWriteSequence(null)
        writer.writeToSequence(javax.imageio.IIOImage(solid(40, 40, 0xFFFF0000.toInt()), null, meta(0, 0, 40, 40)), param)
        writer.writeToSequence(javax.imageio.IIOImage(solid(10, 10, 0xFF00FF00.toInt()), null, meta(30, 30, 10, 10)), param)
        writer.endWriteSequence()
        writer.dispose()
        ios.close()
    }
}
