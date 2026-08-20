package gallery.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import gallery.AppState
import gallery.ArchiveReader
import gallery.BookEntry
import gallery.ImageItem
import gallery.ImageScanner
import gallery.moveToTrash
import gallery.pickArchive
import gallery.pickFolder
import gallery.sortImages
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun App(state: AppState, onToggleFullscreen: () -> Unit) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var pendingDelete by remember { mutableStateOf<ImageItem?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // 图集加载完成后恢复/重置查看进度。
    fun finishLoad() {
        if (state.resumeFromBook) {
            val p = state.progressFor(state.locationPath)
            state.selectedIndex = if (state.images.isNotEmpty()) p.coerceIn(0, state.images.size - 1) else -1
        } else {
            state.selectedIndex = -1
        }
    }

    suspend fun rescan() {
        val f = state.folder ?: return
        state.loading = true
        try {
            state.images = sortImages(ImageScanner.scan(f, state.recursive), state.sortMode)
            finishLoad()
        } finally {
            state.loading = false
        }
    }

    suspend fun rescanArchive(archiveFile: File) {
        state.loading = true
        try {
            val newReader = ArchiveReader.open(archiveFile)
            val items = ImageScanner.scanArchive(archiveFile, newReader)
            state.archiveReader?.close()
            state.archiveReader = newReader
            state.images = sortImages(items, state.sortMode)
            finishLoad()
        } catch (t: Throwable) {
            error = "刷新失败：${t.message ?: t.toString()}"
        } finally {
            state.loading = false
        }
    }

    fun openFolder() {
        scope.launch {
            pickFolder()?.let { f ->
                state.closeArchive()
                state.resumeFromBook = false
                state.folder = f
                state.closeViewer()
                rescan()
            }
        }
    }

    fun openArchive() {
        scope.launch {
            val f = pickArchive() ?: return@launch
            val reader = try {
                ArchiveReader.open(f)
            } catch (t: Throwable) {
                error = "无法打开压缩包：${t.message ?: t.toString()}"
                return@launch
            }
            val items = ImageScanner.scanArchive(f, reader)
            if (items.isEmpty()) {
                reader.close()
                error = "压缩包内没有找到图片"
                return@launch
            }
            state.archiveReader?.close()
            state.archiveReader = reader
            state.archive = f
            state.folder = null
            state.resumeFromBook = false
            state.closeViewer()
            state.images = sortImages(items, state.sortMode)
            finishLoad()
        }
    }

    fun openBook(book: BookEntry) {
        scope.launch {
            state.closeArchive()
            state.showBookshelf = false
            state.resumeFromBook = true
            if (book.type == "archive") {
                val f = File(book.path)
                val reader = try {
                    ArchiveReader.open(f)
                } catch (t: Throwable) {
                    error = "无法打开压缩包：${t.message ?: t.toString()}"
                    return@launch
                }
                val items = ImageScanner.scanArchive(f, reader)
                if (items.isEmpty()) {
                    reader.close()
                    error = "压缩包内没有找到图片"
                    return@launch
                }
                state.archiveReader = reader
                state.archive = f
                state.folder = null
                state.images = sortImages(items, state.sortMode)
            } else {
                val f = File(book.path)
                if (!f.isDirectory) {
                    error = "文件夹不存在：${f.absolutePath}"
                    return@launch
                }
                state.folder = f
                state.loading = true
                try {
                    state.images = sortImages(ImageScanner.scan(f, state.recursive), state.sortMode)
                } finally {
                    state.loading = false
                }
            }
            finishLoad()
        }
    }

    fun refresh() {
        scope.launch {
            val arch = state.archive
            if (arch != null) rescanArchive(arch) else rescan()
        }
    }

    fun removeItem(item: ImageItem) {
        val idx = state.images.indexOf(item)
        if (idx < 0) return
        val newList = state.images.toMutableList().apply { removeAt(idx) }
        state.images = newList
        if (newList.isEmpty()) {
            state.closeViewer()
        } else if (state.selectedIndex >= 0) {
            state.selectedIndex = idx.coerceIn(0, newList.size - 1)
        }
    }

    fun deleteCurrent() {
        val item = state.current ?: return
        val f = item.file ?: return // 归档内条目不支持删除
        if (moveToTrash(f)) {
            removeItem(item)
        } else {
            pendingDelete = item
        }
    }

    fun handleKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val viewerOpen = state.selectedIndex >= 0
        if (e.key == Key.F11) {
            onToggleFullscreen()
            return true
        }
        if (!viewerOpen) {
            return when (e.key) {
                Key.F5 -> { refresh(); true }
                Key.O -> if (e.isCtrlPressed) { openFolder(); true } else false
                else -> false
            }
        }
        return when (e.key) {
            Key.Escape -> { state.closeViewer(); true }
            Key.DirectionRight -> { state.selectedIndex = (state.selectedIndex + 1) % state.images.size; true }
            Key.DirectionLeft -> {
                state.selectedIndex = (state.selectedIndex - 1 + state.images.size) % state.images.size
                true
            }
            Key.Delete -> { deleteCurrent(); true }
            Key.I -> { state.showInfo = !state.showInfo; true }
            Key.Spacebar -> { state.slideshow = !state.slideshow; true }
            else -> false
        }
    }

    // Re-sort in place when the sort mode changes.
    LaunchedEffect(state.sortMode) {
        state.images = sortImages(state.images, state.sortMode)
    }

    // 翻页时自动保存书架进度。
    LaunchedEffect(state.selectedIndex) {
        state.updateProgress()
    }

    // Slideshow loop.
    LaunchedEffect(state.slideshow, state.selectedIndex) {
        while (state.slideshow && state.images.isNotEmpty()) {
            delay((state.slideshowSeconds * 1000).toLong())
            state.selectedIndex = (state.selectedIndex + 1) % state.images.size
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    GalleryTheme {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { handleKey(it) },
        ) {
            val current = state.current
            when {
                state.showBookshelf -> BookshelfScreen(
                    books = state.books,
                    onOpen = { openBook(it) },
                    onRemove = { state.removeFromBookshelf(it.id) },
                    onBack = { state.showBookshelf = false },
                )
                state.selectedIndex >= 0 && current != null -> ImageViewer(
                    item = current,
                    index = state.selectedIndex,
                    total = state.images.size,
                    onPrev = { state.selectedIndex = (state.selectedIndex - 1 + state.images.size) % state.images.size },
                    onNext = { state.selectedIndex = (state.selectedIndex + 1) % state.images.size },
                    onClose = { state.closeViewer() },
                    onDelete = { deleteCurrent() },
                    onToggleInfo = { state.showInfo = !state.showInfo },
                    showInfo = state.showInfo,
                    showUi = state.showUi,
                    onToggleUi = { state.showUi = !state.showUi },
                    onToggleSlideshow = { state.slideshow = !state.slideshow },
                    slideshow = state.slideshow,
                )
                else -> GalleryScreen(
                    folderName = state.locationName,
                    images = state.images,
                    sortMode = state.sortMode,
                    onSortChange = { state.sortMode = it },
                    thumbSize = state.thumbSize,
                    onThumbSizeChange = { state.thumbSize = it },
                    recursive = state.recursive,
                    onRecursiveChange = {
                        state.recursive = it
                        refresh()
                    },
                    loading = state.loading,
                    onOpenFolder = { openFolder() },
                    onOpenArchive = { openArchive() },
                    onRefresh = { refresh() },
                    onImageClick = { state.open(it) },
                    onSlideshow = {
                        if (state.images.isNotEmpty()) {
                            state.selectedIndex = 0
                            state.slideshow = true
                        }
                    },
                    onOpenBookshelf = { state.showBookshelf = true },
                    isInBookshelf = state.isInBookshelf(),
                    onAddToBookshelf = { state.addToBookshelf() },
                )
            }
        }

        pendingDelete?.let { item ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("永久删除") },
                text = { Text("无法移入回收站，是否永久删除「${item.name}」？此操作不可撤销。") },
                confirmButton = {
                    TextButton(onClick = {
                        item.file?.delete()
                        removeItem(item)
                        pendingDelete = null
                    }) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("取消") }
                },
            )
        }

        error?.let { msg ->
            AlertDialog(
                onDismissRequest = { error = null },
                title = { Text("提示") },
                text = { Text(msg) },
                confirmButton = {
                    TextButton(onClick = { error = null }) { Text("确定") }
                },
            )
        }
    }
}
