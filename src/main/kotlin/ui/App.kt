package gallery.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.AlertDialog
import androidx.compose.material.OutlinedTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
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
import gallery.AppLog
import gallery.AppState
import gallery.ArchiveReader
import gallery.BookEntry
import gallery.EncryptedZipException
import gallery.ImageItem
import gallery.ImageScanner
import gallery.ReadingDirection
import gallery.RecentEntry
import gallery.ThemeMode
import gallery.i18n.LocalStrings
import gallery.i18n.Strings
import gallery.i18n.StringsKey
import gallery.moveToTrash
import gallery.pickArchive
import gallery.pickFolder
import gallery.sortImages
import java.awt.Component
import java.io.File
import org.apache.commons.compress.PasswordRequiredException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 等待用户输入密码的加密压缩包，以及输完之后照原样重试所需的参数。 */
private data class PasswordRequest(
    val file: File,
    val resume: Boolean,
    val recordRecent: Boolean,
    val wrongPassword: Boolean,
)

/** 当前正在进行的图集加载；新的加载开始前取消它（见 App 里的 launchLoad）。 */
private class LoadSlot {
    var job: Job? = null
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun App(
    state: AppState,
    onToggleFullscreen: () -> Unit,
    /** 文件对话框的父窗口（ComposeWindow 即 AWT Component）；为 null 时对话框不附着主窗口。 */
    dialogParent: Component? = null,
    isFullscreen: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var pendingDelete by remember { mutableStateOf<ImageItem?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val loadSlot = remember { LoadSlot() }
    var passwordRequest by remember { mutableStateOf<PasswordRequest?>(null) }

    // 网格滚动状态提到这里：GalleryScreen 在查看器打开时会离开组合，状态放在它内部就会被
    // 销毁，关掉查看器后网格回到顶部。按图集路径区分，换图集时从顶部开始。
    val gridState = remember(state.locationPath) { LazyGridState() }

    val strings = remember(state.localeTag) { Strings(state.localeTag) }
    CompositionLocalProvider(LocalStrings provides strings) {

    val isDark = when (state.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // ---- 加载图集 ----
    //
    // 所有入口（工具栏 / 书架 / 最近打开 / 刷新）都走 [launchLoad]：
    // - 新的加载开始前取消上一个。否则先开 A、马上开 B，A 扫得慢、最后才完成时，就会出现
    //   「folder = B、images = A 的内容」，书架进度也跟着写错条目；
    // - 打开压缩包、扫描目录都在 IO 线程上做完，**全部成功之后**才一次性改 state
    //   （见 [commitLocation]）。失败或被取消时 state 保持原样，不会留下「archive 已清空、
    //   网格里却还是旧压缩包条目」这种半截状态。

    fun launchLoad(block: suspend () -> Unit) {
        loadSlot.job?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            state.loading = true
            try {
                block()
            } finally {
                // 被新的加载取消时不要把 loading 关掉：新的那个还在跑。
                if (loadSlot.job === coroutineContext[Job]) state.loading = false
            }
        }
        loadSlot.job = job
        job.start()
    }

    /**
     * 把新图集提交到 state 上。调用前所有可能失败 / 挂起的工作都已完成，这里只做同步赋值，
     * 中间不会被取消打断。
     */
    fun commitLocation(
        folder: File?,
        archive: File?,
        reader: ArchiveReader?,
        items: List<ImageItem>,
        resume: Boolean,
        password: CharArray? = null,
    ) {
        // 先把上一个图集的进度存下来 —— 必须在下面改动 archive/folder 之前。
        // 放到后面的话，saveCurrentProgress() 会拿到「新图集的 id + 上一次的 selectedIndex」，
        // 把书架上另一个条目的 progress/total 一起冲掉。
        state.saveCurrentProgress()
        state.archiveReader?.close()
        state.archiveReader = reader
        state.archivePassword = password
        state.archive = archive
        state.folder = folder
        state.searchQuery = ""
        // 直接重置查看态而不调 closeViewer()：它内部还会用（已经是新的）locationPath 再存一次进度。
        state.selectedIndex = -1
        state.slideshow = false
        state.lastViewed = null
        state.images = sortImages(items, state.sortMode, state.sortDirection)
        state.selectedIndex = if (resume) state.resumeIndexFor(state.locationPath, state.images) else -1
        state.syncBook(state.locationPath, touch = resume)
    }

    /**
     * 打开压缩包并载入图片列表。[resume] 是否恢复书架进度；[recordRecent] 是否记入「最近打开」；
     * [password] 是用户为加密 7z 输入的密码。需要密码时弹出密码框，确认后带着密码重来一遍。
     */
    suspend fun loadArchive(file: File, resume: Boolean, recordRecent: Boolean, password: CharArray? = null) {
        val reader = try {
            withContext(Dispatchers.IO) { ArchiveReader.open(file, password) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PasswordRequiredException) {
            passwordRequest = PasswordRequest(file, resume, recordRecent, wrongPassword = password != null)
            return
        } catch (e: EncryptedZipException) {
            error = strings.t(StringsKey.EncryptedZipUnsupported, file.name)
            return
        } catch (t: Throwable) {
            // 带了密码还打不开，最常见的原因是密码错了（7z 解出来的头部校验失败）：再问一次。
            if (password != null) {
                passwordRequest = PasswordRequest(file, resume, recordRecent, wrongPassword = true)
                return
            }
            AppLog.warn("打开压缩包失败：${file.absolutePath}", t)
            error = strings.t(StringsKey.OpenArchiveFailed, t.message ?: t.toString())
            return
        }
        try {
            val items = ImageScanner.scanArchive(file, reader)
            if (items.isEmpty()) {
                reader.close()
                error = strings.t(StringsKey.ArchiveEmpty)
                return
            }
            commitLocation(folder = null, archive = file, reader = reader, items = items, resume = resume, password = password)
        } catch (t: Throwable) {
            // 被取消（或扫描失败）时新 reader 还没交给 state，必须自己关掉。
            reader.close()
            if (t is CancellationException) throw t
            AppLog.warn("打开压缩包失败：${file.absolutePath}", t)
            error = strings.t(StringsKey.OpenArchiveFailed, t.message ?: t.toString())
            return
        }
        if (recordRecent) state.addRecent(file.absolutePath, file.name, "archive")
    }

    /** 打开文件夹并载入图片列表；参数含义同 [loadArchive]。 */
    suspend fun loadFolder(dir: File, resume: Boolean, recordRecent: Boolean) {
        if (!dir.isDirectory) {
            error = strings.t(StringsKey.FolderNotFound, dir.absolutePath)
            return
        }
        val items = try {
            ImageScanner.scan(dir, state.recursive)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.warn("扫描文件夹失败：${dir.absolutePath}", t)
            error = strings.t(StringsKey.OpenFolderFailed, t.message ?: t.toString())
            return
        }
        commitLocation(folder = dir, archive = null, reader = null, items = items, resume = resume)
        if (recordRecent) state.addRecent(dir.absolutePath, dir.name, "folder")
        if (items.size >= ImageScanner.MAX_FILES) error = strings.t(StringsKey.ScanTruncated, ImageScanner.MAX_FILES)
    }

    /**
     * 重新扫描当前图集（F5 / 切换子文件夹开关）。只换图片列表，保留搜索词与网格位置，
     * 也不再恢复书架进度 —— 否则从书架打开过的图集每按一次 F5 都会自己弹进查看器。
     */
    suspend fun rescanCurrent() {
        val arch = state.archive
        val dir = state.folder
        try {
            if (arch != null) {
                val newReader = withContext(Dispatchers.IO) { ArchiveReader.open(arch, state.archivePassword) }
                val items = try {
                    ImageScanner.scanArchive(arch, newReader)
                } catch (t: Throwable) {
                    newReader.close()
                    throw t
                }
                // 期间用户可能已经切走了；那就不要把旧图集的结果写回来。
                if (state.archive != arch) { newReader.close(); return }
                state.archiveReader?.close()
                state.archiveReader = newReader
                state.images = sortImages(items, state.sortMode, state.sortDirection)
            } else if (dir != null) {
                val items = ImageScanner.scan(dir, state.recursive)
                if (state.folder != dir) return
                state.images = sortImages(items, state.sortMode, state.sortDirection)
                if (items.size >= ImageScanner.MAX_FILES) error = strings.t(StringsKey.ScanTruncated, ImageScanner.MAX_FILES)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.warn("刷新失败：${state.locationPath}", t)
            error = strings.t(StringsKey.RefreshFailed, t.message ?: t.toString())
        }
    }

    fun openFolder() {
        scope.launch {
            pickFolder(dialogParent, strings.t(StringsKey.SelectFolder))?.let { launchLoad { loadFolder(it, resume = false, recordRecent = true) } }
        }
    }

    fun openArchive() {
        scope.launch {
            pickArchive(dialogParent, strings.t(StringsKey.SelectArchive), strings.t(StringsKey.ArchiveFilter))?.let { launchLoad { loadArchive(it, resume = false, recordRecent = true) } }
        }
    }

    /** 从书架进入：恢复这本书的阅读进度（上一个图集的进度在 commitLocation 里保存）。 */
    fun openBook(book: BookEntry) {
        state.showBookshelf = false
        launchLoad {
            if (book.type == "archive") {
                loadArchive(File(book.path), resume = true, recordRecent = false)
            } else {
                loadFolder(File(book.path), resume = true, recordRecent = false)
            }
        }
    }

    /** 从「最近打开」进入某个位置（不恢复书架进度），并把它挪到最近列表的最前面。 */
    fun openRecent(entry: RecentEntry) {
        state.showBookshelf = false
        launchLoad {
            if (entry.type == "archive") {
                loadArchive(File(entry.path), resume = false, recordRecent = true)
            } else {
                loadFolder(File(entry.path), resume = false, recordRecent = true)
            }
        }
    }

    fun refresh() {
        launchLoad { rescanCurrent() }
    }

    /**
     * 打开任意路径（命令行参数 / 拖进窗口）：文件夹、压缩包直接打开；单张图片则打开它所在
     * 的文件夹，并直接在查看器里显示这张图。
     */
    fun openPath(file: File) {
        state.showBookshelf = false
        when {
            file.isDirectory -> launchLoad { loadFolder(file, resume = false, recordRecent = true) }
            file.isFile && ArchiveReader.isSupported(file) ->
                launchLoad { loadArchive(file, resume = false, recordRecent = true) }
            file.isFile && file.extension.lowercase() in ImageScanner.EXTENSIONS -> {
                val dir = file.absoluteFile.parentFile ?: return
                launchLoad {
                    loadFolder(dir, resume = false, recordRecent = true)
                    if (state.folder == dir) {
                        val idx = state.images.indexOfFirst { it.file?.absoluteFile == file.absoluteFile }
                        if (idx >= 0) state.selectedIndex = idx
                    }
                }
            }
            else -> error = strings.t(StringsKey.UnsupportedPath, file.absolutePath)
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
        val forward = if (state.readingDirection == ReadingDirection.RTL) -1 else 1
        return when (e.key) {
            Key.Escape -> { state.closeViewer(); true }
            // 翻页只在「搜索过滤后的可见列表」内移动，避免跳到被过滤掉的图片。
            // 从右往左读时 ← 是下一页（和书页的物理方向一致）。
            Key.DirectionRight -> { state.selectedIndex = state.step(forward); true }
            Key.DirectionLeft -> { state.selectedIndex = state.step(-forward); true }
            Key.PageDown -> { state.selectedIndex = state.step(1); true }
            Key.PageUp -> { state.selectedIndex = state.step(-1); true }
            Key.MoveHome -> { state.firstVisibleIndex().takeIf { it >= 0 }?.let { state.selectedIndex = it }; true }
            Key.MoveEnd -> { state.lastVisibleIndex().takeIf { it >= 0 }?.let { state.selectedIndex = it }; true }
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
        state.saveCurrentProgress()
    }

    // 缩略图大小（滑块拖动 / Ctrl+滚轮）停下 0.5s 后才写 settings.json：触控板一次滑动会连发
    // 几十个滚轮事件，逐个写盘就是几十次同步 IO。关窗时 saveWindow 会连同 thumbSize 一起存。
    LaunchedEffect(state.thumbSize) {
        delay(500)
        state.persistThumbSize()
    }

    // 预取相邻图片：翻页时直接命中全尺寸缓存，不用干等解码（失败静默忽略）。
    LaunchedEffect(state.selectedIndex, state.images) {
        val i = state.selectedIndex
        if (i < 0) return@LaunchedEffect
        val neighbours = listOfNotNull(state.images.getOrNull(i + 1), state.images.getOrNull(i - 1))
        neighbours.forEach { nb -> launch { gallery.ImageLoader.prefetch(nb.source) } }
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

    LaunchedEffect(state.pendingOpen) {
        val f = state.pendingOpen ?: return@LaunchedEffect
        state.pendingOpen = null
        openPath(f)
    }

    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                // readFiles() 给的是 file: URI；只取第一个（一次打开一个图集）。
                val first = files.firstNotNullOfOrNull { runCatching { File(URI(it)) }.getOrNull() }
                    ?: return false
                state.pendingOpen = first
                return true
            }
        }
    }

    GalleryTheme(isDark) {
        Box(
            Modifier
                .fillMaxSize()
                .background(LocalGalleryColors.current.background)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { handleKey(it) }
                .dragAndDropTarget(
                    shouldStartDragAndDrop = { it.dragData() is DragData.FilesList },
                    target = dropTarget,
                ),
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
                    readingDirection = state.readingDirection,
                    onToggleReadingDirection = { state.toggleReadingDirection() },
                    wheelAction = state.wheelAction,
                    onToggleWheelAction = { state.toggleWheelAction() },
                    fullscreen = isFullscreen,
                )
                else -> GalleryScreen(
                    folderName = state.locationName,
                    images = state.images,
                    sortMode = state.sortMode,
                    sortDirection = state.sortDirection,
                    onSortChange = { state.updateSortMode(it) },
                    onToggleSortDirection = { state.toggleSortDirection() },
                    searchQuery = state.searchQuery,
                    onSearchChange = { state.searchQuery = it },
                    thumbSize = state.thumbSize,
                    onThumbSizeChange = { state.thumbSize = it },
                    recursive = state.recursive,
                    onRecursiveChange = {
                        state.updateRecursive(it)
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
                    locale = state.locale,
                    onLocaleChange = { state.updateLocale(it) },
                    recent = state.recent,
                    onOpenRecent = { openRecent(it) },
                    onRemoveRecent = { state.removeRecent(it.path) },
                    onClearRecent = { state.clearRecent() },
                    gridState = gridState,
                    lastViewed = state.lastViewed,
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
                        pendingDelete = null
                        // delete() 失败（只读 / 被占用 / 权限不足）时图片还在磁盘上，不能从列表里拿掉。
                        if (item.file?.delete() == true) {
                            removeItem(item)
                        } else {
                            error = strings.t(StringsKey.DeleteFailed, item.name)
                        }
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

        passwordRequest?.let { req ->
            PasswordDialog(
                request = req,
                onConfirm = { pwd ->
                    passwordRequest = null
                    launchLoad { loadArchive(req.file, req.resume, req.recordRecent, pwd) }
                },
                onDismiss = { passwordRequest = null },
            )
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
        StringsKey.HelpPage to StringsKey.HelpPageDesc,
        StringsKey.HelpHomeEnd to StringsKey.HelpHomeEndDesc,
        StringsKey.HelpZoom to StringsKey.HelpZoomDesc,
        StringsKey.HelpThumbZoom to StringsKey.HelpThumbZoomDesc,
        StringsKey.HelpClose to StringsKey.HelpCloseDesc,
        StringsKey.HelpDelete to StringsKey.HelpDeleteDesc,
        StringsKey.HelpInfo to StringsKey.HelpInfoDesc,
        StringsKey.HelpRotate to StringsKey.HelpRotateDesc,
        StringsKey.HelpFlip to StringsKey.HelpFlipDesc,
        StringsKey.HelpCopy to StringsKey.HelpCopyDesc,
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

@Composable
private fun PasswordDialog(request: PasswordRequest, onConfirm: (CharArray) -> Unit, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun confirm() {
        if (text.isNotEmpty()) onConfirm(text.toCharArray())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.t(StringsKey.PasswordTitle)) },
        text = {
            Column {
                Text(
                    strings.t(if (request.wrongPassword) StringsKey.PasswordWrong else StringsKey.PasswordPrompt, request.file.name),
                    fontSize = GalleryTokens.textBody,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.fillMaxWidth().padding(top = GalleryTokens.spacingS).focusRequester(focus)
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                                confirm(); true
                            } else {
                                false
                            }
                        },
                )
            }
        },
        confirmButton = { TextButton(onClick = { confirm() }) { Text(strings.t(StringsKey.OK)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.t(StringsKey.Cancel)) } },
    )
}
