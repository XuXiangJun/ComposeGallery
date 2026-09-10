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
import gallery.i18n.LocalStrings
import gallery.i18n.StringsKey
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
    onLocaleChange: (String) -> Unit,
    recent: List<RecentEntry>,
    onOpenRecent: (RecentEntry) -> Unit,
    selectedItem: ImageItem? = null,
) {
    val s = LocalStrings.current
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
    onLocaleChange: (String) -> Unit,
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
                                        Text(s.t(StringsKey.valueOf(mode.name)))
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
                        listOf("zh" to s.t(StringsKey.LocaleZH), "en" to s.t(StringsKey.LocaleEN)).forEach { (tag, label) ->
                            DropdownMenuItem(onClick = {
                                onLocaleChange(tag)
                                localeExpanded = false
                                moreExpanded = false
                            }) {
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
private fun SearchField(
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
    onOpenFolder: () -> Unit,
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
            TextButton(onClick = onOpenFolder) {
                Text(s.t(StringsKey.SelectFolder))
            }
            if (!searching && folderName == null && recent.isNotEmpty()) {
                Spacer(Modifier.height(GalleryTokens.spacingXl))
                Text(s.t(StringsKey.Recent), color = colors.onSurfaceMuted, fontSize = GalleryTokens.textSmall)
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
