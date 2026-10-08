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

    /** 当前压缩包的密码（加密 7z）。只留在内存里，刷新时重开要用；不落盘。 */
    var archivePassword: CharArray? = null

    var images by mutableStateOf<List<ImageItem>>(emptyList())
    var sortMode by mutableStateOf(settings.sortMode)
    var sortDirection by mutableStateOf(settings.sortDirection)
    var recursive by mutableStateOf(settings.recursive)

    /** 启动时恢复的窗口位置 / 大小；关窗时由 Main 写回。 */
    val initialWindow: WindowGeometry get() = settings.window
    var thumbSize by mutableStateOf(settings.thumbSize)
    var loading by mutableStateOf(false)

    /** 文件名搜索过滤词（空 = 显示全部）。 */
    var searchQuery by mutableStateOf("")

    /** Index into [images]; -1 means grid mode, >= 0 means the viewer is open. */
    var selectedIndex by mutableStateOf(-1)

    /**
     * 待打开的路径：命令行参数（「用 ComposeGallery 打开」/ 文件关联）或拖进窗口的文件
     * 都先放在这里，由 App 取走并清空。可以是文件夹、压缩包或单张图片。
     */
    var pendingOpen by mutableStateOf<File?>(null)

    /** 最近一次在查看器里看的图：关闭查看器回到网格时，高亮它并滚动到它所在位置。 */
    var lastViewed by mutableStateOf<ImageItem?>(null)

    var slideshow by mutableStateOf(false)
    var slideshowSeconds by mutableStateOf(settings.slideshowSeconds)
    var showInfo by mutableStateOf(false)
    var showUi by mutableStateOf(true)

    var themeMode by mutableStateOf(settings.themeMode)
    var readingDirection by mutableStateOf(settings.readingDirection)
    var wheelAction by mutableStateOf(settings.wheelAction)
    var showHelp by mutableStateOf(false)

    /** 用户选的语言，可以是「跟随系统」；实际显示用 [localeTag]。 */
    var locale by mutableStateOf(settings.locale)

    val localeTag: String
        get() = locale.resolved(Settings.defaultLocaleTag())

    // ---- 最近打开 ----
    var recent by mutableStateOf(settings.recent)

    // ---- 书架 ----
    var books by mutableStateOf<List<BookEntry>>(emptyList())
    var showBookshelf by mutableStateOf(false)

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
        saveCurrentProgress()
        lastViewed = current
        selectedIndex = -1
        slideshow = false
    }

    // ---- 设置 ----

    private fun persistSettings() {
        settings = settings.copy(
            themeMode = themeMode,
            sortDirection = sortDirection,
            slideshowSeconds = slideshowSeconds,
            recent = recent,
            // 存用户的选择本身（含 SYSTEM），而不是解析后的 zh / en ——
            // 否则保存一次之后「跟随系统」就再也回不来了。
            locale = locale,
            thumbSize = thumbSize,
            readingDirection = readingDirection,
            wheelAction = wheelAction,
            sortMode = sortMode,
            recursive = recursive,
        )
        SettingsStore.save(settings)
    }

    fun updateLocale(value: AppLocale) {
        locale = value
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

    fun updateSortMode(mode: SortMode) {
        sortMode = mode
        persistSettings()
    }

    fun updateRecursive(value: Boolean) {
        recursive = value
        persistSettings()
    }

    fun saveWindow(geometry: WindowGeometry) {
        settings = settings.copy(window = geometry)
        persistSettings()
    }

    fun toggleReadingDirection() {
        readingDirection = if (readingDirection == ReadingDirection.LTR) ReadingDirection.RTL else ReadingDirection.LTR
        persistSettings()
    }

    fun toggleWheelAction() {
        wheelAction = if (wheelAction == WheelAction.ZOOM) WheelAction.PAGE else WheelAction.ZOOM
        persistSettings()
    }

    /**
     * 缩略图大小停止变化后由 App 合并调用：拖动 / 滚轮过程中只改 [thumbSize]，这里才落盘。
     * 只在确实变了时写（启动时那次合并写盘也会走到这里）。
     */
    fun persistThumbSize() {
        if (thumbSize != settings.thumbSize) persistSettings()
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

    /** 当前可见列表（搜索过滤后）最后一张在 [images] 中的索引；没有可见图片时为 -1。 */
    fun lastVisibleIndex(): Int {
        if (images.isEmpty()) return -1
        val q = searchQuery.trim()
        return if (q.isEmpty()) images.lastIndex else images.indexOfLast { it.name.contains(q, ignoreCase = true) }
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

    fun removeRecent(path: String) {
        recent = recent.filter { it.path != path }
        persistSettings()
    }

    fun clearRecent() {
        recent = emptyList()
        persistSettings()
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
            progressKey = images.getOrNull(selectedIndex.coerceAtLeast(0))?.displayPath,
        )
        books = books.filter { it.id != id } + book
        BookshelfStore.save(books)
    }

    fun removeFromBookshelf(id: String) {
        books = books.filter { it.id != id }
        BookshelfStore.save(books)
    }

    /** 翻页/关闭时更新当前图集的书架进度；[key] 是当前那张图的 [ImageItem.displayPath]。 */
    fun updateProgress(id: String?, index: Int, total: Int, key: String?) {
        if (id == null || index < 0) return
        val idx = books.indexOfFirst { it.id == id }
        if (idx < 0) return
        val b = books[idx]
        if (b.progress == index && b.total == total && b.progressKey == key) return
        books = books.toMutableList().also {
            it[idx] = b.copy(
                progress = index,
                total = total,
                progressKey = key,
                lastRead = System.currentTimeMillis(),
            )
        }
        BookshelfStore.save(books)
    }

    /**
     * 把「当前打开的图集」的进度写回书架；关闭 / 切换图集前调用。
     *
     * 参数在 [updateProgress] 里全部显式传入，而不是读 [locationPath] / [selectedIndex] /
     * [images]：切换图集的那一刻 [folder] / [archive] 已经被换成新图集了，靠隐式当前状态就会
     * 把「新图集的 id + 上一次的阅读位置」写进书架上另一个条目，把它的进度和总数一起冲掉。
     */
    fun saveCurrentProgress() =
        updateProgress(locationPath, selectedIndex, images.size, images.getOrNull(selectedIndex)?.displayPath)

    /**
     * 打开一个在书架上的图集后，用刚扫描到的内容刷新它的封面与总页数（首图被删、文件夹里
     * 增删了图片时，书架上的信息会过期）；[touch] 为 true（从书架打开）时同时刷新
     * [BookEntry.lastRead]，让它排到书架最前面 —— [updateProgress] 在页码没变时是短路的，
     * 只靠它的话「打开了但没翻页」不会更新顺序。
     */
    fun syncBook(id: String?, touch: Boolean) {
        val idx = books.indexOfFirst { it.id == id }
        if (idx < 0) return
        val b = books[idx]
        val updated = b.copy(
            cover = coverSource() ?: b.cover,
            total = images.size,
            lastRead = if (touch) System.currentTimeMillis() else b.lastRead,
        )
        if (updated == b) return
        books = books.toMutableList().also { it[idx] = updated }
        BookshelfStore.save(books)
    }

    fun progressFor(id: String?): Int = id?.let { books.firstOrNull { b -> b.id == id }?.progress } ?: 0

    /**
     * 书架上 [id] 这本书应当从 [list] 的哪一张继续读：优先按记录的图片路径定位，
     * 找不到（旧数据 / 那张图已被删除）再退回记录的下标。[list] 为空时返回 -1。
     */
    fun resumeIndexFor(id: String?, list: List<ImageItem>): Int {
        if (list.isEmpty()) return -1
        val book = id?.let { books.firstOrNull { b -> b.id == id } } ?: return 0
        book.progressKey?.let { key ->
            val byKey = list.indexOfFirst { it.displayPath == key }
            if (byKey >= 0) return byKey
        }
        return book.progress.coerceIn(0, list.size - 1)
    }
}
