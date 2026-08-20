package gallery.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gallery.ImageItem
import gallery.SortMode
import kotlin.math.roundToInt

@Composable
fun GalleryScreen(
    folderName: String?,
    images: List<ImageItem>,
    sortMode: SortMode,
    onSortChange: (SortMode) -> Unit,
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
) {
    Column(Modifier.fillMaxSize()) {
        Toolbar(
            folderName = folderName,
            count = images.size,
            sortMode = sortMode,
            onSortChange = onSortChange,
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
        )
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (images.isEmpty() && !loading) {
            EmptyState(folderName, onOpenFolder)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = thumbSize.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(images, key = { it.source.cacheKey }) { item ->
                    ThumbnailCell(item, thumbSize, onClick = { onImageClick(item) })
                }
            }
        }
    }
}

@Composable
private fun Toolbar(
    folderName: String?,
    count: Int,
    sortMode: SortMode,
    onSortChange: (SortMode) -> Unit,
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
) {
    Surface(elevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton(onClick = onOpenFolder) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.width(18.dp))
                Text("打开文件夹")
            }
            TextButton(onClick = onOpenArchive) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.width(18.dp))
                Text("打开压缩包")
            }
            TextButton(onClick = onOpenBookshelf) {
                Text("书架")
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, "刷新")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = recursive, onCheckedChange = onRecursiveChange)
                Text("含子文件夹", fontSize = 12.sp)
            }
            SortMenu(sortMode, onSortChange)
            Spacer(Modifier.width(6.dp))
            Text("缩略图", fontSize = 12.sp, color = Color(0xFFAAAAAA))
            Slider(
                value = thumbSize,
                onValueChange = onThumbSizeChange,
                valueRange = 120f..300f,
                modifier = Modifier.width(140.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (folderName != null) "$folderName · $count 张" else "$count 张",
                fontSize = 12.sp,
                color = Color(0xFFAAAAAA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onAddToBookshelf, enabled = count > 0 && !isInBookshelf) {
                Text(if (isInBookshelf) "已在书架" else "加入书架", color = if (isInBookshelf) Color(0xFF777777) else Accent)
            }
            TextButton(onClick = onSlideshow, enabled = count > 0) {
                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.width(18.dp))
                Text("幻灯片")
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
                    Text(m.label, color = if (m == sortMode) Accent else Color.Unspecified)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(folderName: String?, onOpenFolder: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (folderName == null) "尚未打开文件夹" else "「$folderName」中没有找到图片",
                color = Color(0xFF999999),
                fontSize = 16.sp,
            )
            TextButton(onClick = onOpenFolder) {
                Text("选择图片文件夹")
            }
        }
    }
}

@Composable
private fun ThumbnailCell(item: ImageItem, thumbSize: Float, onClick: () -> Unit) {
    val density = LocalDensity.current
    val targetPx = (thumbSize * density.density).roundToInt().coerceIn(160, 640)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF232326))
            .border(
                width = if (hovered) 2.dp else 1.dp,
                color = if (hovered) Accent else Color(0xFF333333),
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Thumbnail(item.source, item.name, targetPx, Modifier.fillMaxSize())
        }
        Text(
            item.name,
            Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
            color = Color(0xFFBBBBBB),
        )
    }
}
