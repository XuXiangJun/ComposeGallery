package gallery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

class AppState {
    var folder by mutableStateOf<File?>(null)

    /** 当前浏览的压缩包（非 null 表示正在浏览归档内容）。 */
    var archive by mutableStateOf<File?>(null)
    var archiveReader: ArchiveReader? = null

    var images by mutableStateOf<List<ImageItem>>(emptyList())
    var sortMode by mutableStateOf(SortMode.NAME)
    var recursive by mutableStateOf(false)
    var thumbSize by mutableStateOf(180f)
    var loading by mutableStateOf(false)

    /** Index into [images]; -1 means grid mode, >= 0 means the viewer is open. */
    var selectedIndex by mutableStateOf(-1)

    var slideshow by mutableStateOf(false)
    var slideshowSeconds by mutableStateOf(3f)
    var showInfo by mutableStateOf(false)
    var showUi by mutableStateOf(true)

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
