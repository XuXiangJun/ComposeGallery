package gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
    Column(Modifier.fillMaxSize()) {
        Surface(elevation = 4.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color(0xFFBBBBBB))
                }
                Text("书架", color = Color(0xFFE6E6E6), fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "共 ${books.size} 本",
                    color = Color(0xFF999999),
                    fontSize = 12.sp,
                )
            }
        }

        if (books.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "书架是空的\n在图库中打开文件夹或压缩包后，点击「加入书架」即可收藏",
                    color = Color(0xFF777777),
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 170.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(books.sortedByDescending { it.lastRead }, key = { it.id }) { book ->
                    BookCard(book, onOpen = { onOpen(book) }, onRemove = { onRemove(book) })
                }
            }
        }
    }
}

@Composable
private fun BookCard(book: BookEntry, onOpen: () -> Unit, onRemove: () -> Unit) {
    val density = LocalDensity.current
    val targetPx = (200 * density.density).roundToInt().coerceIn(200, 512)
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E22))
            .clickable(onClick = onOpen),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            BookCover(book, targetPx)
            // 删除按钮（右上角）
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
                    .background(Color(0x88000000), RoundedCornerShape(6.dp)),
            ) {
                Icon(Icons.Filled.Close, "移出书架", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                book.name,
                color = Color(0xFFDDDDDD),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val total = book.total.coerceAtLeast(1)
            val pct = ((book.progress + 1).toFloat() / total).coerceIn(0f, 1f)
            Text(
                "${book.progress + 1} / ${book.total}",
                color = Color(0xFF999999),
                fontSize = 11.sp,
            )
            LinearProgressIndicator(
                progress = pct,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = Accent,
                backgroundColor = Color(0xFF333333),
            )
        }
    }
}

@Composable
private fun BookCover(book: BookEntry, targetPx: Int) {
    val source: ImageSource? = when (book.type) {
        "archive" -> {
            val archiveFile = File(book.path)
            val entryName = book.cover.substringAfter("!/", "")
            val reader = remember(book.path) { runCatching { ArchiveReader.open(archiveFile) }.getOrNull() }
            DisposableEffect(reader) {
                onDispose { reader?.close() }
            }
            if (reader != null) ArchiveSource(archiveFile, entryName, reader, 0, 0) else null
        }
        else -> FileSource(File(book.cover))
    }
    if (source != null) {
        Thumbnail(source, book.name, targetPx, Modifier.fillMaxSize())
    } else {
        Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2D)))
    }
}
