package gallery.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gallery.ImageItem
import gallery.RecentEntry
import gallery.SortDirection
import gallery.SortMode
import gallery.ThemeMode
import kotlin.math.roundToInt

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
    recent: List<RecentEntry>,
    onOpenRecent: (RecentEntry) -> Unit,
    selectedItem: ImageItem? = null,
) {
    val query = searchQuery.trim()
    val visible = if (query.isEmpty()) images else images.filter { it.name.contains(query, ignoreCase = true) }

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
                onOpenFolder = onOpenFolder,
            )
        } else if (visible.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = thumbSize.dp),
                contentPadding = PaddingValues(GalleryTokens.spacingM),
                horizontalArrangement = Arrangement.spacedBy(GalleryTokens.spacingS),
                verticalArrangement = Arrangement.spacedBy(GalleryTokens.spacingS),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.source.cacheKey }) { item ->
                    ThumbnailCell(
                        item = item,
                        thumbSize = thumbSize,
                        onClick = { onImageClick(item) },
                        isSelected = item == selectedItem,
                    )
                }
            }
        }
    }
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
) {
    val colors = LocalGalleryColors.current
    var moreExpanded by remember { mutableStateOf(false) }
    Surface(elevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = GalleryTokens.spacingM, vertical = GalleryTokens.spacingS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GalleryTokens.spacingS),
        ) {
            TextButton(onClick = onOpenFolder) { Text("打开文件夹") }
            TextButton(onClick = onOpenArchive) { Text("打开压缩包") }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, "刷新", tint = colors.onSurface)
            }
            TextButton(onClick = onOpenBookshelf) { Text("书架") }
            Spacer(Modifier.width(GalleryTokens.spacingS))
            SearchField(searchQuery, onSearchChange, modifier = Modifier.width(220.dp))
            if (total > 0) {
                TextButton(onClick = onSlideshow) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Text("幻灯片")
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (folderName == null) "$total 张"
                else if (searching) "$visibleCount / $total 张"
                else "$folderName · $total 张",
                fontSize = GalleryTokens.textSmall,
                color = colors.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box {
                IconButton(onClick = { moreExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, "更多", tint = colors.onSurface)
                }
                DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                    DropdownMenuItem(onClick = { onRecursiveChange(!recursive); moreExpanded = false }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = recursive, onCheckedChange = null)
                            Spacer(Modifier.width(GalleryTokens.spacingS))
                            Text(if (recursive) "含子文件夹 ✓" else "含子文件夹")
                        }
                    }
                    if (total > 0 && !isInBookshelf) {
                        DropdownMenuItem(onClick = { onAddToBookshelf(); moreExpanded = false }) {
                            Icon(Icons.Filled.Star, null, tint = colors.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(GalleryTokens.spacingS))
                            Text("加入书架")
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
                            Text("排序")
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
                                Text(if (sortDirection == SortDirection.ASC) "升序" else "降序")
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
                                    Text("按名称排序")
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
                                    Text("按大小排序")
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
                                    Text("按修改时间排序")
                                }
                            }
                        }
                    }
                    var themeSubExpanded by remember { mutableStateOf(false) }
                    Box {
                        DropdownMenuItem(onClick = { themeSubExpanded = true }) {
                            Text("主题")
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
                                        Text(mode.label)
                                    }
                                }
                            }
                        }
                    }
                    DropdownMenuItem(onClick = { onHelp(); moreExpanded = false }) {
                        Text("帮助")
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            Icon(Icons.Filled.Search, "搜索", tint = colors.onSurfaceMuted, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f).padding(horizontal = GalleryTokens.spacingS)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = colors.onSurface, fontSize = GalleryTokens.textBody),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "搜索图片" },
                    interactionSource = interaction,
                    decorationBox = { inner ->
                        if (value.isEmpty()) {
                            Text("搜索图片…", color = colors.onSurfaceMuted, fontSize = GalleryTokens.textBody)
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
                    Icon(Icons.Filled.Close, "清除搜索", tint = colors.onSurfaceMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SortMenu(sortMode: SortMode, onSortChange: (SortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("排序: ${sortMode.label}")
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortMode.entries.forEach { m ->
                DropdownMenuItem(onClick = { onSortChange(m); expanded = false }) {
                    Text(m.label, color = if (m == sortMode) LocalGalleryColors.current.primary else LocalGalleryColors.current.onSurface)
                }
            }
        }
    }
}

@Composable
private fun ThemeMenu(themeMode: ThemeMode, onSetTheme: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val colors = LocalGalleryColors.current
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(themeMode.label)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThemeMode.entries.forEach { m ->
                DropdownMenuItem(onClick = { onSetTheme(m); expanded = false }) {
                    Text(m.label, color = if (m == themeMode) colors.primary else colors.onSurface)
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
    onOpenFolder: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    searching -> "没有匹配「$query」的图片"
                    folderName == null -> "尚未打开文件夹"
                    else -> "「$folderName」中没有找到图片"
                },
                color = colors.onSurfaceVariant,
                fontSize = 16.sp,
            )
            TextButton(onClick = onOpenFolder) {
                Text("选择图片文件夹")
            }
            if (!searching && folderName == null && recent.isNotEmpty()) {
                Spacer(Modifier.height(GalleryTokens.spacingXl))
                Text("最近打开", color = colors.onSurfaceMuted, fontSize = GalleryTokens.textSmall)
                Spacer(Modifier.height(GalleryTokens.spacingS))
                recent.take(6).forEach { entry ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenRecent(entry) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(GalleryTokens.spacingS),
                    ) {
                        Icon(Icons.Filled.Star, null, tint = colors.primary, modifier = Modifier.size(14.dp))
                        Text(entry.name, color = colors.onSurface, fontSize = GalleryTokens.textBody)
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
