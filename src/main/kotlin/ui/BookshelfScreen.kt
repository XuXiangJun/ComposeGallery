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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gallery.ArchiveReader
import gallery.ArchiveSource
import gallery.BookEntry
import gallery.FileSource
import gallery.ImageSource
import java.io.File
import kotlin.math.roundToInt

@Composable
fun BookshelfScreen(
    books: List<BookEntry>,
    onOpen: (BookEntry) -> Unit,
    onRemove: (BookEntry) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalGalleryColors.current

    // 压缩包 reader 共享缓存：本次进入书架期间复用，离开书架时统一关闭。
    val readerCache = remember {
        object : LinkedHashMap<String, ArchiveReader>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArchiveReader>?): Boolean =
                size > 8
        }
    }
    DisposableEffect(readerCache) {
        onDispose {
            readerCache.values.forEach { runCatching { it.close() } }
            readerCache.clear()
        }
    }
    fun getReader(path: String): ArchiveReader? =
        readerCache[path] ?: runCatching { ArchiveReader.open(File(path)) }
            .getOrNull()
            ?.also { readerCache[path] = it }

    Column(
        Modifier
            .fillMaxSize()
            .background(LocalGalleryColors.current.background),
    ) {
        Surface(elevation = 4.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = GalleryTokens.spacingS, vertical = GalleryTokens.spacingS / 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = colors.onSurfaceVariant)
                }
                Text("书架", color = colors.onSurface, fontSize = GalleryTokens.textHeadline)
                Spacer(Modifier.weight(1f))
                Text(
                    "共 ${books.size} 本",
                    color = colors.onSurfaceVariant,
                    fontSize = GalleryTokens.textSmall,
                )
            }
        }

        if (books.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "书架是空的\n在图库中打开文件夹或压缩包后，点击「加入书架」即可收藏",
                    color = colors.onSurfaceVariant,
                    fontSize = GalleryTokens.textBase,
                    lineHeight = 22.sp,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 170.dp),
                contentPadding = PaddingValues(GalleryTokens.spacingM),
                horizontalArrangement = Arrangement.spacedBy(GalleryTokens.spacingM),
                verticalArrangement = Arrangement.spacedBy(GalleryTokens.spacingM),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(books.sortedByDescending { it.lastRead }, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        getReader = { getReader(it) },
                        onOpen = { onOpen(book) },
                        onRemove = { onRemove(book) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BookCard(
    book: BookEntry,
    getReader: (String) -> ArchiveReader?,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    val density = LocalDensity.current
    val targetPx = (200 * density.density).roundToInt().coerceIn(200, 512)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .clip(GalleryTokens.shapeM)
            .background(colors.surfaceHigh)
            .border(
                width = if (hovered) 2.dp else 1.dp,
                color = if (hovered) colors.outlineHover else colors.outline,
                shape = GalleryTokens.shapeM,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            BookCover(book, targetPx, getReader)
            // 删除按钮（右上角）
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
                    .background(Color(0x88000000), GalleryTokens.shapeS),
            ) {
                Icon(Icons.Filled.Close, "移出书架", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Column(Modifier.padding(horizontal = GalleryTokens.spacingS, vertical = GalleryTokens.spacingS)) {
            Text(
                book.name,
                color = colors.onSurface,
                fontSize = GalleryTokens.textBody,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val total = book.total.coerceAtLeast(1)
            val pct = ((book.progress + 1).toFloat() / total).coerceIn(0f, 1f)
            Text(
                "${book.progress + 1} / ${book.total}",
                color = colors.onSurfaceMuted,
                fontSize = GalleryTokens.textCaption,
            )
            LinearProgressIndicator(
                progress = pct,
                modifier = Modifier.fillMaxWidth().padding(top = GalleryTokens.spacingXs),
                color = colors.primary,
                backgroundColor = colors.surfaceDim,
            )
        }
    }
}

@Composable
private fun BookCover(book: BookEntry, targetPx: Int, getReader: (String) -> ArchiveReader?) {
    val source: ImageSource? = when (book.type) {
        "archive" -> {
            val archiveFile = File(book.path)
            val entryName = book.cover.substringAfter("!/", "")
            val reader = getReader(book.path)
            if (reader != null) ArchiveSource(archiveFile, entryName, reader, 0, 0) else null
        }
        else -> FileSource(File(book.cover))
    }
    if (source != null) {
        Thumbnail(source, book.name, targetPx, Modifier.fillMaxSize())
    } else {
        Box(Modifier.fillMaxSize().background(LocalGalleryColors.current.placeholder))
    }
}
