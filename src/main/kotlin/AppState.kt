package gallery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

class AppState {
    private var settings = SettingsStore.load()

    var folder by mutableStateOf<File?>(null)

    /** 当前浏览的压缩包（非 null 表示正在浏览归档内容）。 */
    var archive by mutableStateOf<File?>(null)
    var archiveReader: ArchiveReader? = null

    var images by mutableStateOf<List<ImageItem>>(emptyList())
    var sortMode by mutableStateOf(SortMode.NAME)
    var sortDirection by mutableStateOf(settings.sortDirection)
    var recursive by mutableStateOf(false)
    var thumbSize by mutableStateOf(180f)
    var loading by mutableStateOf(false)

    /** 文件名搜索过滤词（空 = 显示全部）。 */
    var searchQuery by mutableStateOf("")

    /** Index into [images]; -1 means grid mode, >= 0 means the viewer is open. */
    var selectedIndex by mutableStateOf(-1)

    var slideshow by mutableStateOf(false)
    var slideshowSeconds by mutableStateOf(settings.slideshowSeconds)
    var showInfo by mutableStateOf(false)
    var showUi by mutableStateOf(true)

    var themeMode by mutableStateOf(settings.themeMode)
    var showHelp by mutableStateOf(false)

    var localeTag by mutableStateOf(settings.localeTag())

    // ---- 最近打开 ----
    var recent by mutableStateOf(settings.recent)

    // ---- 书架 ----
    var books by mutableStateOf<List<BookEntry>>(emptyList())
    var showBookshelf by mutableStateOf(false)

    /** 打开图集后是否恢复书架进度（true = 从书架打开，直接进查看器）。 */
    var resumeFromBook by mutableStateOf(false)

    init {
        books = BookshelfStore.load()
    }

    val current: ImageItem?
        get() = images.getOrNull(selectedIndex)

    val locationName: String?
        get() = archive?.name ?: folder?.name

    val locationPath: String?
        get() = archive?.absolutePath ?: folder?.absolutePath

    val locationType: String
        get() = if (archive != null) "archive" else "folder"

    fun open(image: ImageItem) {
        val i = images.indexOf(image)
        if (i >= 0) selectedIndex = i
    }

    fun closeViewer() {
        updateProgress()
        selectedIndex = -1
        slideshow = false
    }

    fun closeArchive() {
        updateProgress()
        archiveReader?.close()
        archiveReader = null
        archive = null
    }

    // ---- 设置 ----

    private fun persistSettings() {
        settings = settings.copy(
            themeMode = themeMode,
            sortDirection = sortDirection,
            slideshowSeconds = slideshowSeconds,
            recent = recent,
            locale = AppLocale.valueOf(localeTag.uppercase()),
        )
        SettingsStore.save(settings)
    }

    fun updateLocale(tag: String) {
        localeTag = tag
        persistSettings()
    }

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        persistSettings()
    }

    fun toggleSortDirection() {
        sortDirection = if (sortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
        persistSettings()
    }

    fun updateSlideshowSeconds(value: Float) {
        slideshowSeconds = value.coerceIn(1f, 10f)
        persistSettings()
    }

    // ---- 浏览顺序（考虑搜索过滤）----

    /** 当前可见列表（搜索过滤后）第一张在 [images] 中的索引；没有可见图片时为 -1。 */
    fun firstVisibleIndex(): Int {
        if (images.isEmpty()) return -1
        val q = searchQuery.trim()
        return if (q.isEmpty()) 0 else images.indexOfFirst { it.name.contains(q, ignoreCase = true) }
    }

    /**
     * 在「当前可见列表」（搜索过滤后）内移动 [delta] 步，返回新的 [images] 索引。
     * 这样搜索状态下按 ←/→ 或跑幻灯片不会跳到被过滤掉的图片；[selectedIndex] 始终索引
     * [images]，因此书架进度语义保持不变。
     */
    fun step(delta: Int): Int {
        val all = images
        if (all.isEmpty()) return -1
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            val base = if (selectedIndex in all.indices) selectedIndex else 0
            return ((base + delta) % all.size + all.size) % all.size
        }
        val visible = filterImages(all, q)
        if (visible.isEmpty()) return selectedIndex
        val pos = visible.indexOf(all.getOrNull(selectedIndex))
        val nextPos = if (pos < 0) 0 else ((pos + delta) % visible.size + visible.size) % visible.size
        return all.indexOf(visible[nextPos])
    }

    /** 打开某个位置后记录到「最近打开」，最多保留 8 条、按使用时间去重。 */
    fun addRecent(path: String, name: String, type: String) {
        val entry = RecentEntry(path, name, type, System.currentTimeMillis())
        recent = (listOf(entry) + recent.filter { it.path != path }).take(8)
        persistSettings()
    }

    // ---- 书架操作 ----

    private fun coverSource(): String? {
        val first = images.firstOrNull() ?: return null
        return when (val s = first.source) {
            is FileSource -> s.file.absolutePath
            is ArchiveSource -> "${s.archiveFile.absolutePath}!/${s.entryName}"
            else -> null
        }
    }

    fun isInBookshelf(): Boolean = locationPath?.let { id -> books.any { it.id == id } } == true

    fun addToBookshelf() {
        val id = locationPath ?: return
        val name = locationName ?: return
        val cover = coverSource() ?: return
        val book = BookEntry(
            id = id,
            name = name,
            type = locationType,
            path = id,
            cover = cover,
            progress = selectedIndex.coerceAtLeast(0),
            total = images.size,
            lastRead = System.currentTimeMillis(),
        )
        books = books.filter { it.id != id } + book
        BookshelfStore.save(books)
    }

    fun removeFromBookshelf(id: String) {
        books = books.filter { it.id != id }
        BookshelfStore.save(books)
    }

    /** 翻页/关闭时更新当前图集的书架进度。 */
    fun updateProgress() {
        val id = locationPath ?: return
        if (selectedIndex < 0) return
        val idx = books.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val b = books[idx]
            if (b.progress != selectedIndex || b.total != images.size) {
                books = books.toMutableList().also {
                    it[idx] = b.copy(
                        progress = selectedIndex,
                        total = images.size,
                        lastRead = System.currentTimeMillis(),
                    )
                }
                BookshelfStore.save(books)
            }
        }
    }

    fun progressFor(id: String?): Int = id?.let { books.firstOrNull { b -> b.id == id }?.progress } ?: 0
}
