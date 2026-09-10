package gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.unit.dp
import gallery.AppState
import gallery.ArchiveReader
import gallery.BookEntry
import gallery.ImageItem
import gallery.ImageScanner
import gallery.RecentEntry
import gallery.ThemeMode
import gallery.i18n.LocalStrings
import gallery.i18n.Strings
import gallery.i18n.StringsKey
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

    val strings = remember(state.localeTag) { Strings(state.localeTag) }
    CompositionLocalProvider(LocalStrings provides strings) {

    val isDark = when (state.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

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
            state.images = sortImages(ImageScanner.scan(f, state.recursive), state.sortMode, state.sortDirection)
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
            state.images = sortImages(items, state.sortMode, state.sortDirection)
            finishLoad()
        } catch (t: Throwable) {
            error = strings.t(StringsKey.RefreshFailed, t.message ?: t.toString())
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
                state.searchQuery = ""
                state.closeViewer()
                rescan()
                state.addRecent(f.absolutePath, f.name, "folder")
            }
        }
    }

    fun openArchive() {
        scope.launch {
            val f = pickArchive() ?: return@launch
            val reader = try {
                ArchiveReader.open(f)
            } catch (t: Throwable) {
                error = strings.t(StringsKey.OpenArchiveFailed, t.message ?: t.toString())
                return@launch
            }
            val items = ImageScanner.scanArchive(f, reader)
            if (items.isEmpty()) {
                reader.close()
                error = strings.t(StringsKey.ArchiveEmpty)
                return@launch
            }
            state.archiveReader?.close()
            state.archiveReader = reader
            state.archive = f
            state.folder = null
            state.resumeFromBook = false
            state.searchQuery = ""
            state.closeViewer()
            state.images = sortImages(items, state.sortMode, state.sortDirection)
            finishLoad()
            state.addRecent(f.absolutePath, f.name, "archive")
        }
    }

    fun openBook(book: BookEntry) {
        scope.launch {
            state.closeArchive()
            state.showBookshelf = false
            state.resumeFromBook = true
            state.searchQuery = ""
            if (book.type == "archive") {
                val f = File(book.path)
                val reader = try {
                    ArchiveReader.open(f)
                } catch (t: Throwable) {
                    error = strings.t(StringsKey.OpenArchiveFailed, t.message ?: t.toString())
                    return@launch
                }
                val items = ImageScanner.scanArchive(f, reader)
                if (items.isEmpty()) {
                    reader.close()
                    error = strings.t(StringsKey.ArchiveEmpty)
                    return@launch
                }
                state.archiveReader = reader
                state.archive = f
                state.folder = null
                state.images = sortImages(items, state.sortMode, state.sortDirection)
            } else {
                val f = File(book.path)
                if (!f.isDirectory) {
                    error = strings.t(StringsKey.FolderNotFound, f.absolutePath)
                    return@launch
                }
                state.folder = f
                state.loading = true
                try {
                    state.images = sortImages(ImageScanner.scan(f, state.recursive), state.sortMode, state.sortDirection)
                } finally {
                    state.loading = false
                }
            }
            finishLoad()
        }
    }

    /** 从「最近打开」进入某个位置（不恢复书架进度）。 */
    fun openRecent(entry: RecentEntry) {
        scope.launch {
            state.closeArchive()
            state.showBookshelf = false
            state.resumeFromBook = false
            state.searchQuery = ""
            if (entry.type == "archive") {
                val f = File(entry.path)
                val reader = try {
                    ArchiveReader.open(f)
                } catch (t: Throwable) {
                    error = strings.t(StringsKey.OpenArchiveFailed, t.message ?: t.toString())
                    return@launch
                }
                val items = ImageScanner.scanArchive(f, reader)
                if (items.isEmpty()) {
                    reader.close()
                    error = strings.t(StringsKey.ArchiveEmpty)
                    return@launch
                }
                state.archiveReader = reader
                state.archive = f
                state.folder = null
                state.images = sortImages(items, state.sortMode, state.sortDirection)
            } else {
                val f = File(entry.path)
                if (!f.isDirectory) {
                    error = strings.t(StringsKey.FolderNotFound, f.absolutePath)
                    return@launch
                }
                state.folder = f
                state.loading = true
                try {
                    state.images = sortImages(ImageScanner.scan(f, state.recursive), state.sortMode, state.sortDirection)
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
        if (e.key == Key.F1) {
            state.showHelp = true
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
            // 翻页只在「搜索过滤后的可见列表」内移动，避免跳到被过滤掉的图片。
            Key.DirectionRight -> { state.selectedIndex = state.step(1); true }
            Key.DirectionLeft -> { state.selectedIndex = state.step(-1); true }
            Key.Delete -> { deleteCurrent(); true }
            Key.I -> { state.showInfo = !state.showInfo; true }
            Key.Spacebar -> { state.slideshow = !state.slideshow; true }
            else -> false
        }
    }

    // Re-sort in place when the sort mode or direction changes.
    LaunchedEffect(state.sortMode, state.sortDirection) {
        state.images = sortImages(state.images, state.sortMode, state.sortDirection)
    }

    // 翻页时更新书架进度：延迟合并写盘，避免连续翻页 / 幻灯片每换一张就写一次书架 JSON。
    // LaunchedEffect 的 key 变化会取消上一个协程，所以只有停下来 0.8s 后才真正落盘。
    LaunchedEffect(state.selectedIndex) {
        if (state.selectedIndex < 0) return@LaunchedEffect
        delay(800)
        state.updateProgress()
    }

    // Slideshow loop.
    LaunchedEffect(state.slideshow, state.selectedIndex) {
        while (state.slideshow && state.images.isNotEmpty()) {
            delay((state.slideshowSeconds * 1000).toLong())
            state.selectedIndex = state.step(1)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    GalleryTheme(isDark) {
        Box(
            Modifier
                .fillMaxSize()
                .background(LocalGalleryColors.current.background)
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
                    onPrev = { state.selectedIndex = state.step(-1) },
                    onNext = { state.selectedIndex = state.step(1) },
                    onClose = { state.closeViewer() },
                    onDelete = { deleteCurrent() },
                    onToggleInfo = { state.showInfo = !state.showInfo },
                    showInfo = state.showInfo,
                    showUi = state.showUi,
                    onToggleUi = { state.showUi = !state.showUi },
                    onToggleSlideshow = { state.slideshow = !state.slideshow },
                    slideshow = state.slideshow,
                    slideshowSeconds = state.slideshowSeconds,
                    onSlideshowSecondsChange = { state.updateSlideshowSeconds(it) },
                )
                else -> GalleryScreen(
                    folderName = state.locationName,
                    images = state.images,
                    sortMode = state.sortMode,
                    sortDirection = state.sortDirection,
                    onSortChange = { state.sortMode = it },
                    onToggleSortDirection = { state.toggleSortDirection() },
                    searchQuery = state.searchQuery,
                    onSearchChange = { state.searchQuery = it },
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
                        // 从「当前可见列表」的第一张开始播放。
                        val first = state.firstVisibleIndex()
                        if (first >= 0) {
                            state.selectedIndex = first
                            state.slideshow = true
                        }
                    },
                    onOpenBookshelf = { state.showBookshelf = true },
                    isInBookshelf = state.isInBookshelf(),
                    onAddToBookshelf = { state.addToBookshelf() },
                    themeMode = state.themeMode,
                    onSetTheme = { state.updateThemeMode(it) },
                    onHelp = { state.showHelp = true },
                    onLocaleChange = { state.updateLocale(it) },
                    recent = state.recent,
                    onOpenRecent = { openRecent(it) },
                    selectedItem = state.current.takeIf { state.selectedIndex >= 0 },
                )
            }
        }

        pendingDelete?.let { item ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(strings.t(StringsKey.ConfirmDeleteTitle)) },
                text = { Text(strings.t(StringsKey.ConfirmDeleteText, item.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        item.file?.delete()
                        removeItem(item)
                        pendingDelete = null
                    }) { Text(strings.t(StringsKey.DeleteConfirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text(strings.t(StringsKey.Cancel)) }
                },
            )
        }

        error?.let { msg ->
            AlertDialog(
                onDismissRequest = { error = null },
                title = { Text(strings.t(StringsKey.AlertTitle)) },
                text = { Text(msg) },
                confirmButton = {
                    TextButton(onClick = { error = null }) { Text(strings.t(StringsKey.OK)) }
                },
            )
        }

        if (state.showHelp) {
            HelpDialog(onDismiss = { state.showHelp = false })
        }
    }
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    val colors = LocalGalleryColors.current
    val rows = listOf(
        StringsKey.HelpOpenFolder to StringsKey.HelpOpenFolderDesc,
        StringsKey.HelpRefresh to StringsKey.HelpRefreshDesc,
        StringsKey.HelpFullscreen to StringsKey.HelpFullscreenDesc,
        StringsKey.HelpHelp to StringsKey.HelpHelpDesc,
        StringsKey.HelpPrevNext to StringsKey.HelpPrevNextDesc,
        StringsKey.HelpClose to StringsKey.HelpCloseDesc,
        StringsKey.HelpDelete to StringsKey.HelpDeleteDesc,
        StringsKey.HelpInfo to StringsKey.HelpInfoDesc,
        StringsKey.HelpSlideshow to StringsKey.HelpSlideshowDesc,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.t(StringsKey.ShortcutsTitle)) },
        text = {
            Column(Modifier.height(220.dp).verticalScroll(rememberScrollState())) {
                rows.forEach { (k, v) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(strings.t(k), color = colors.primary, fontSize = GalleryTokens.textBody)
                        Text(strings.t(v), color = colors.onSurfaceVariant, fontSize = GalleryTokens.textBody)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.t(StringsKey.Close)) } },
    )
}
