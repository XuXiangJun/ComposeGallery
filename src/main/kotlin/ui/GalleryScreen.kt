package gallery.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Checkbox
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Slider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gallery.AppLocale
import gallery.ImageItem
import gallery.RecentEntry
import gallery.SortDirection
import gallery.SortMode
import gallery.THUMB_SIZE_MAX
import gallery.THUMB_SIZE_MIN
import gallery.ThemeMode
import gallery.i18n.LocalStrings
import gallery.i18n.StringsKey
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GalleryScreen(
    folderName: String?,
    images: List<ImageItem>,
    sortMode: SortMode,
    sortDirection: SortDirection,
    onSortChange: (SortMode) -> Unit,
    onToggleSortDirection: () -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    thumbSize: Float,
    onThumbSizeChange: (Float) -> Unit,
    onThumbSizeChangeFinished: () -> Unit,
    recursive: Boolean,
    onRecursiveChange: (Boolean) -> Unit,
    loading: Boolean,
    onOpenFolder: () -> Unit,
    onOpenArchive: () -> Unit,
    onRefresh: () -> Unit,
    onImageClick: (ImageItem) -> Unit,
    onSlideshow: () -> Unit,
    onOpenBookshelf: () -> Unit,
    isInBookshelf: Boolean,
    onAddToBookshelf: () -> Unit,
    themeMode: ThemeMode,
    onSetTheme: (ThemeMode) -> Unit,
    onHelp: () -> Unit,
    locale: AppLocale,
    onLocaleChange: (AppLocale) -> Unit,
    recent: List<RecentEntry>,
    onOpenRecent: (RecentEntry) -> Unit,
    onRemoveRecent: (RecentEntry) -> Unit,
    onClearRecent: () -> Unit,
    gridState: LazyGridState,
    /** 刚在查看器里看过的图：高亮，并在它不在可视范围内时滚动过去。 */
    lastViewed: ImageItem? = null,
) {
    val s = LocalStrings.current
    val query = searchQuery.trim()
    // 与 AppState.step 共用同一套过滤规则（trim + 忽略大小写），保证网格显示顺序与查看器翻页顺序一致。
    // 按 (images, query) 缓存过滤结果：避免每次重组都重算一遍全量过滤（上万条时很明显）。
    val visible = remember(images, query) { gallery.filterImages(images, query) }

    LaunchedEffect(lastViewed) {
        val target = lastViewed ?: return@LaunchedEffect
        val idx = visible.indexOf(target)
        if (idx < 0) return@LaunchedEffect
        val shown = gridState.layoutInfo.visibleItemsInfo.map { it.index }
        if (idx !in shown) gridState.scrollToItem(idx)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(LocalGalleryColors.current.background),
    ) {
        Toolbar(
            folderName = folderName,
            total = images.size,
            visibleCount = visible.size,
            searching = query.isNotEmpty(),
            sortMode = sortMode,
            sortDirection = sortDirection,
            onSortChange = onSortChange,
            onToggleSortDirection = onToggleSortDirection,
            searchQuery = searchQuery,
            onSearchChange = onSearchChange,
            thumbSize = thumbSize,
            onThumbSizeChange = onThumbSizeChange,
            onThumbSizeChangeFinished = onThumbSizeChangeFinished,
            recursive = recursive,
            onRecursiveChange = onRecursiveChange,
            onOpenFolder = onOpenFolder,
            onOpenArchive = onOpenArchive,
            onRefresh = onRefresh,
            onSlideshow = onSlideshow,
            onOpenBookshelf = onOpenBookshelf,
            isInBookshelf = isInBookshelf,
            onAddToBookshelf = onAddToBookshelf,
            themeMode = themeMode,
            onSetTheme = onSetTheme,
            onHelp = onHelp,
            locale = locale,
            onLocaleChange = onLocaleChange,
        )
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (visible.isEmpty() && !loading) {
            EmptyState(
                folderName = folderName,
                searching = query.isNotEmpty(),
                query = query,
                recent = recent,
                onOpenRecent = onOpenRecent,
                onRemoveRecent = onRemoveRecent,
                onClearRecent = onClearRecent,
                onOpenFolder = onOpenFolder,
                onOpenArchive = onOpenArchive,
            )
        } else if (visible.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = thumbSize.dp),
                contentPadding = PaddingValues(GalleryTokens.spacingM),
                horizontalArrangement = Arrangement.spacedBy(GalleryTokens.spacingS),
                verticalArrangement = Arrangement.spacedBy(GalleryTokens.spacingS),
                modifier = Modifier
                    .fillMaxSize()
                    // Ctrl + 滚轮调缩略图大小。在 Initial 阶段拦截并消费掉，网格本身就不会跟着滚动。
                    .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                        if (!event.keyboardModifiers.isCtrlPressed) return@onPointerEvent
                        val dy = event.changes.first().scrollDelta.y
                        if (dy != 0f) {
                            onThumbSizeChange((thumbSize - dy * 16f).coerceIn(THUMB_SIZE_MIN, THUMB_SIZE_MAX))
                            onThumbSizeChangeFinished()
                        }
                        event.changes.forEach { it.consume() }
                    },
                state = gridState,
            ) {
                items(visible, key = { it.source.cacheKey }) { item ->
                    ThumbnailCell(
                        item = item,
                        thumbSize = thumbSize,
                        onClick = { onImageClick(item) },
                        isSelected = item == lastViewed,
                    )
                }
            }
        }
    }
}

/** ThemeMode → 文案键的显式映射（不再用 StringsKey.valueOf(mode.name)，避免依赖枚举名与键名一致）。 */
private fun themeModeKey(mode: ThemeMode): StringsKey = when (mode) {
    ThemeMode.SYSTEM -> StringsKey.ThemeSystem
    ThemeMode.LIGHT -> StringsKey.ThemeLight
    ThemeMode.DARK -> StringsKey.ThemeDark
}

@Composable
private fun Toolbar(
    folderName: String?,
    total: Int,
    visibleCount: Int,
    searching: Boolean,
    sortMode: SortMode,
    sortDirection: SortDirection,
    onSortChange: (SortMode) -> Unit,
    onToggleSortDirection: () -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    thumbSize: Float,
    onThumbSizeChange: (Float) -> Unit,
    onThumbSizeChangeFinished: () -> Unit,
    recursive: Boolean,
    onRecursiveChange: (Boolean) -> Unit,
    onOpenFolder: () -> Unit,
    onOpenArchive: () -> Unit,
    onRefresh: () -> Unit,
    onSlideshow: () -> Unit,
    onOpenBookshelf: () -> Unit,
    isInBookshelf: Boolean,
    onAddToBookshelf: () -> Unit,
    themeMode: ThemeMode,
    onSetTheme: (ThemeMode) -> Unit,
    onHelp: () -> Unit,
    locale: AppLocale,
    onLocaleChange: (AppLocale) -> Unit,
) {
    val s = LocalStrings.current
    val colors = LocalGalleryColors.current
    var moreExpanded by remember { mutableStateOf(false) }
    var localeExpanded by remember { mutableStateOf(false) }
    Surface(elevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = GalleryTokens.spacingM, vertical = GalleryTokens.spacingS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GalleryTokens.spacingS),
        ) {
            TextButton(onClick = onOpenFolder) { Text(s.t(StringsKey.OpenFolder)) }
            TextButton(onClick = onOpenArchive) { Text(s.t(StringsKey.OpenArchive)) }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, s.t(StringsKey.Refresh), tint = colors.onSurface)
            }
            TextButton(onClick = onOpenBookshelf) { Text(s.t(StringsKey.Bookshelf)) }
            Spacer(Modifier.width(GalleryTokens.spacingS))
            SearchField(searchQuery, onSearchChange, modifier = Modifier.width(220.dp))
            if (total > 0) {
                TextButton(onClick = onSlideshow) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Text(s.t(StringsKey.Slideshow))
                }
            }
            if (total > 0) {
                // 拖动时实时改网格列宽；松手才回调持久化（避免每挪一格写一次 settings.json）。
                Slider(
                    value = thumbSize,
                    onValueChange = onThumbSizeChange,
                    onValueChangeFinished = onThumbSizeChangeFinished,
                    valueRange = THUMB_SIZE_MIN..THUMB_SIZE_MAX,
                    modifier = Modifier
                        .width(110.dp)
                        .semantics { contentDescription = s.t(StringsKey.ThumbSize) },
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (folderName == null) s.t(StringsKey.StatusTotal, total)
                else if (searching) s.t(StringsKey.StatusSearching, visibleCount, total)
                else s.t(StringsKey.StatusWithFolder, folderName, total),
                fontSize = GalleryTokens.textSmall,
                color = colors.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box {
                IconButton(onClick = { moreExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, s.t(StringsKey.More), tint = colors.onSurface)
                }
                DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                    DropdownMenuItem(onClick = { onRecursiveChange(!recursive); moreExpanded = false }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = recursive, onCheckedChange = null)
                            Spacer(Modifier.width(GalleryTokens.spacingS))
                            Text(s.t(if (recursive) StringsKey.RecursiveOn else StringsKey.RecursiveOff))
                        }
                    }
                    if (total > 0 && !isInBookshelf) {
                        DropdownMenuItem(onClick = { onAddToBookshelf(); moreExpanded = false }) {
                            Icon(Icons.Filled.Star, null, tint = colors.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(GalleryTokens.spacingS))
                            Text(s.t(StringsKey.AddToBookshelf))
                        }
                    }
                    var sortSubExpanded by remember { mutableStateOf(false) }
                    Box {
                        DropdownMenuItem(onClick = { sortSubExpanded = true }) {
                            Icon(
                                imageVector = if (sortDirection == SortDirection.ASC) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = colors.onSurface,
                            )
                            Spacer(Modifier.width(GalleryTokens.spacingS))
                            Text(s.t(StringsKey.Sort))
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "▸",
                                color = colors.onSurfaceMuted,
                                fontSize = 16.sp,
                            )
                        }
                        DropdownMenu(
                            expanded = sortSubExpanded,
                            onDismissRequest = { sortSubExpanded = false },
                        ) {
                            DropdownMenuItem(onClick = {
                                onToggleSortDirection()
                                sortSubExpanded = false
                                moreExpanded = false
                            }) {
                                Icon(
                                    if (sortDirection == SortDirection.ASC) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(GalleryTokens.spacingS))
                                Text(s.t(if (sortDirection == SortDirection.ASC) StringsKey.SortAsc else StringsKey.SortDesc))
                            }
                            DropdownMenuItem(onClick = {
                                onSortChange(SortMode.NAME)
                                sortSubExpanded = false
                                moreExpanded = false
                            }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (sortMode == SortMode.NAME) {
                                        Icon(
                                            Icons.Filled.Check,
                                            null,
                                            tint = colors.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    } else {
                                        Spacer(Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(GalleryTokens.spacingS))
                                    Text(s.t(StringsKey.SortName))
                                }
                            }
                            DropdownMenuItem(onClick = {
                                onSortChange(SortMode.SIZE)
                                sortSubExpanded = false
                                moreExpanded = false
                            }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (sortMode == SortMode.SIZE) {
                                        Icon(
                                            Icons.Filled.Check,
                                            null,
                                            tint = colors.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    } else {
                                        Spacer(Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(GalleryTokens.spacingS))
                                    Text(s.t(StringsKey.SortSize))
                                }
                            }
                            DropdownMenuItem(onClick = {
                                onSortChange(SortMode.DATE)
                                sortSubExpanded = false
                                moreExpanded = false
                            }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (sortMode == SortMode.DATE) {
                                        Icon(
                                            Icons.Filled.Check,
                                            null,
                                            tint = colors.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    } else {
                                        Spacer(Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(GalleryTokens.spacingS))
                                    Text(s.t(StringsKey.SortDate))
                                }
                            }
                        }
                    }
                    var themeSubExpanded by remember { mutableStateOf(false) }
                    Box {
                        DropdownMenuItem(onClick = { themeSubExpanded = true }) {
                            Text(s.t(StringsKey.Theme))
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "▸",
                                color = colors.onSurfaceMuted,
                                fontSize = 16.sp,
                            )
                        }
                        DropdownMenu(
                            expanded = themeSubExpanded,
                            onDismissRequest = { themeSubExpanded = false },
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                DropdownMenuItem(onClick = {
                                    onSetTheme(mode)
                                    themeSubExpanded = false
                                    moreExpanded = false
                                }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (themeMode == mode) {
                                            Icon(
                                                Icons.Filled.Check,
                                                null,
                                                tint = colors.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        } else {
                                            Spacer(Modifier.size(18.dp))
                                        }
                                        Spacer(Modifier.width(GalleryTokens.spacingS))
                                        Text(s.t(themeModeKey(mode)))
                                    }
                                }
                            }
                        }
                    }
                    DropdownMenuItem(onClick = { onHelp(); moreExpanded = false }) {
                        Text(s.t(StringsKey.Help))
                    }
                    DropdownMenuItem(onClick = {
                        localeExpanded = true
                    }) {
                        Text(s.t(StringsKey.Language))
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "▸",
                            color = colors.onSurfaceMuted,
                            fontSize = 16.sp,
                        )
                    }
                    DropdownMenu(
                        expanded = localeExpanded,
                        onDismissRequest = { localeExpanded = false },
                    ) {
                        listOf(
                            AppLocale.SYSTEM to s.t(StringsKey.LocaleSystem),
                            AppLocale.ZH to s.t(StringsKey.LocaleZH),
                            AppLocale.EN to s.t(StringsKey.LocaleEN),
                        ).forEach { (value, label) ->
                            DropdownMenuItem(onClick = {
                                onLocaleChange(value)
                                localeExpanded = false
                                moreExpanded = false
                            }) {
                                CheckMark(checked = locale == value)
                                Spacer(Modifier.width(GalleryTokens.spacingS))
                                Text(label)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val colors = LocalGalleryColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        modifier = modifier.height(36.dp),
        shape = GalleryTokens.shapeS,
        color = colors.surfaceHigh,
        border = BorderStroke(
            width = if (focused) 2.dp else 1.dp,
            color = if (focused) colors.primary else colors.outline,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = GalleryTokens.spacingS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, s.t(StringsKey.Search), tint = colors.onSurfaceMuted, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f).padding(horizontal = GalleryTokens.spacingS)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = colors.onSurface, fontSize = GalleryTokens.textBody),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = s.t(StringsKey.SearchA11y) },
                    interactionSource = interaction,
                    decorationBox = { inner ->
                        if (value.isEmpty()) {
                            Text(s.t(StringsKey.SearchHint), color = colors.onSurfaceMuted, fontSize = GalleryTokens.textBody)
                        }
                        inner()
                    },
                )
            }
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(Icons.Filled.Close, s.t(StringsKey.ClearSearch), tint = colors.onSurfaceMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    folderName: String?,
    searching: Boolean,
    query: String,
    recent: List<RecentEntry>,
    onOpenRecent: (RecentEntry) -> Unit,
    onRemoveRecent: (RecentEntry) -> Unit,
    onClearRecent: () -> Unit,
    onOpenFolder: () -> Unit,
    onOpenArchive: () -> Unit,
) {
    val s = LocalStrings.current
    val colors = LocalGalleryColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    searching -> s.t(StringsKey.Empty_NoMatch, query)
                    folderName == null -> s.t(StringsKey.Empty_NoFolder)
                    else -> s.t(StringsKey.Empty_NoImages, folderName)
                },
                color = colors.onSurfaceVariant,
                fontSize = 16.sp,
            )
            Row {
                TextButton(onClick = onOpenFolder) { Text(s.t(StringsKey.SelectFolder)) }
                TextButton(onClick = onOpenArchive) { Text(s.t(StringsKey.OpenArchive)) }
            }
            if (!searching && folderName == null && recent.isNotEmpty()) {
                Spacer(Modifier.height(GalleryTokens.spacingXl))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.t(StringsKey.Recent), color = colors.onSurfaceMuted, fontSize = GalleryTokens.textSmall)
                    TextButton(onClick = onClearRecent) {
                        Text(s.t(StringsKey.ClearRecent), fontSize = GalleryTokens.textSmall)
                    }
                }
                // 显示全部：AppState 已把「最近打开」限制在 8 条以内，这里再截断只会让两处不一致。
                recent.forEach { entry ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenRecent(entry) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(GalleryTokens.spacingS),
                    ) {
                        Text(
                            s.t(if (entry.type == "archive") StringsKey.RecentArchive else StringsKey.RecentFolder),
                            color = colors.primary,
                            fontSize = GalleryTokens.textCaption,
                        )
                        Text(entry.name, color = colors.onSurface, fontSize = GalleryTokens.textBody)
                        IconButton(onClick = { onRemoveRecent(entry) }, modifier = Modifier.size(20.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                s.t(StringsKey.RemoveRecent),
                                tint = colors.onSurfaceMuted,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbnailCell(
    item: ImageItem,
    thumbSize: Float,
    onClick: () -> Unit,
    isSelected: Boolean = false,
) {
    val colors = LocalGalleryColors.current
    val density = LocalDensity.current
    val targetPx = (thumbSize * density.density).roundToInt().coerceIn(160, 640)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .clip(GalleryTokens.shapeS)
            .background(colors.surfaceHigh)
            .border(
                width = if (isSelected) 2.dp else if (hovered) 2.dp else 1.dp,
                color = if (isSelected) colors.primary else if (hovered) colors.outlineHover else colors.outline,
                shape = GalleryTokens.shapeS,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Thumbnail(item.source, item.name, targetPx, Modifier.fillMaxSize())
            if (isSelected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.primary.copy(alpha = 0.12f)),
                )
                Box(
                    Modifier
                        .size(24.dp)
                        .align(Alignment.TopEnd)
                        .background(colors.primary, GalleryTokens.shapeS)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Text(
            item.name,
            Modifier.padding(horizontal = GalleryTokens.spacingS, vertical = GalleryTokens.spacingS / 2),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = GalleryTokens.textSmall,
            color = colors.onSurfaceVariant,
        )
    }
}

/** 菜单项前的勾选标记；未选中时占同样宽度，保证文字对齐。 */
@Composable
private fun CheckMark(checked: Boolean) {
    if (checked) {
        Icon(Icons.Filled.Check, null, tint = LocalGalleryColors.current.primary, modifier = Modifier.size(18.dp))
    } else {
        Spacer(Modifier.size(18.dp))
    }
}
