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
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gallery.ArchiveCoverSource
import gallery.BookEntry
import gallery.FileSource
import gallery.ImageSource
import gallery.NaturalOrder
import gallery.i18n.LocalStrings
import gallery.i18n.StringsKey
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun BookshelfScreen(
    books: List<BookEntry>,
    onOpen: (BookEntry) -> Unit,
    onRemove: (BookEntry) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    val s = LocalStrings.current
    var query by remember { mutableStateOf("") }
    var sortByName by remember { mutableStateOf(false) }
    val shown = remember(books, query, sortByName) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) books else books.filter { it.name.contains(q, ignoreCase = true) }
        if (sortByName) filtered.sortedWith(compareBy(NaturalOrder) { it.name })
        else filtered.sortedByDescending { it.lastRead }
    }

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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, s.t(StringsKey.Back), tint = colors.onSurfaceVariant)
                }
                Text(s.t(StringsKey.BookshelfTitle), color = colors.onSurface, fontSize = GalleryTokens.textHeadline)
                Spacer(Modifier.width(GalleryTokens.spacingL))
                if (books.isNotEmpty()) {
                    SearchField(query, { query = it }, modifier = Modifier.width(220.dp))
                    Spacer(Modifier.width(GalleryTokens.spacingS))
                    TextButton(onClick = { sortByName = !sortByName }) {
                        Text(s.t(if (sortByName) StringsKey.BookshelfSortName else StringsKey.BookshelfSortRecent))
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    s.t(StringsKey.BookshelfTotal, books.size),
                    color = colors.onSurfaceVariant,
                    fontSize = GalleryTokens.textSmall,
                )
            }
        }

        if (books.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    s.t(StringsKey.BookshelfEmpty),
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
                items(shown, key = { it.id }) { book ->
                    BookCard(
                        book = book,
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
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalGalleryColors.current
    val s = LocalStrings.current
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
        // 路径已经不存在（移动 / 删除 / 移动硬盘没插）：封面变灰并标出来，而不是点进去才报错。
        // 在 IO 线程上 stat（断开的网络盘可能卡好几秒），并且书架停留期间定期复查 ——
        // 原先按路径 remember 一次，拔 / 插移动硬盘后标记不会更新。
        val missing by produceState(initialValue = false, book.path) {
            while (true) {
                value = withContext(Dispatchers.IO) { !File(book.path).exists() }
                delay(MISSING_RECHECK_MS)
            }
        }
        val finished = book.total > 0 && book.progress + 1 >= book.total
        Box(Modifier.fillMaxWidth().aspectRatio(1f).alpha(if (missing) 0.4f else 1f)) {
            BookCover(book, targetPx)
            if (missing || finished) {
                Text(
                    s.t(if (missing) StringsKey.BookMissing else StringsKey.BookFinished),
                    color = if (missing) colors.onDanger else colors.onPrimary,
                    fontSize = GalleryTokens.textCaption,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(GalleryTokens.spacingXs)
                        .background(if (missing) colors.danger else colors.primary, GalleryTokens.shapeS)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // 删除按钮（右上角）
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
                    .background(Color(0x88000000), GalleryTokens.shapeS),
            ) {
                Icon(Icons.Filled.Close, s.t(StringsKey.RemoveBook), tint = Color.White, modifier = Modifier.size(16.dp))
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
private fun BookCover(book: BookEntry, targetPx: Int) {
    val source: ImageSource = remember(book.type, book.path, book.cover) {
        when (book.type) {
            "archive" -> ArchiveCoverSource(File(book.path), book.cover.substringAfter("!/", ""))
            else -> FileSource(File(book.cover))
        }
    }
    Thumbnail(source, book.name, targetPx, Modifier.fillMaxSize())
}

/** 书架停留期间复查「路径是否还在」的间隔（拔 / 插移动硬盘后标记能跟上）。 */
private const val MISSING_RECHECK_MS = 5_000L
