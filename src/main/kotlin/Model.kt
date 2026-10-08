package gallery

import java.io.File

enum class SortMode { NAME, DATE, SIZE }

/** 图片字节来源：磁盘文件或压缩包内条目。 */
interface ImageSource {
    val cacheKey: String
    fun openBytes(): ByteArray
}

/**
 * 磁盘上的图片文件。
 *
 * [cacheKey] 在构造时算好：它参与 [ImageItem.equals] / hashCode、LazyGrid 的 key 和
 * `images.indexOf`，写成每次都 stat 文件的 getter 的话，2 万张图点一下缩略图就是几万次
 * 系统调用，文件被改动后 hashCode 还会变。扫描时已经拿到了大小和修改时间，直接传进来。
 */
class FileSource(
    val file: File,
    size: Long = file.length(),
    modified: Long = file.lastModified(),
) : ImageSource {
    override val cacheKey: String = "file:${file.absolutePath}@$modified:$size"
    override fun openBytes(): ByteArray = file.readBytes()
}

class ArchiveSource(
    val archiveFile: File,
    val entryName: String,
    val reader: ArchiveReader,
    val size: Long,
    val modified: Long,
) : ImageSource {
    override val cacheKey: String = "arc:${archiveFile.absolutePath}!/$entryName@$modified:$size"
    override fun openBytes(): ByteArray = reader.readEntry(entryName)
}

/**
 * 书架封面用的压缩包条目：每次 [openBytes] 都「打开压缩包 → 读条目 → 关闭」。
 *
 * 不复用长期打开的 [ArchiveReader]：书架上可能有几十本书，全开着会占满文件句柄
 * （Windows 上还会锁住这些压缩包，没法移动 / 删除）；而封面解码一次就进缩略图缓存，
 * 重开压缩包的代价只付一次。[openBytes] 由 [ImageLoader] 在 IO 线程上调用，不会卡 UI。
 */
class ArchiveCoverSource(val archiveFile: File, val entryName: String) : ImageSource {
    override val cacheKey: String = "cover:${archiveFile.absolutePath}!/$entryName"
    override fun openBytes(): ByteArray = ArchiveReader.open(archiveFile).use { it.readEntry(entryName) }
}

class ImageItem(
    val source: ImageSource,
    val name: String,
    val sizeBytes: Long,
    val modified: Long,
) {
    /** 磁盘文件（普通图片）；压缩包条目为 null。 */
    val file: File? get() = (source as? FileSource)?.file
    val isArchiveEntry: Boolean get() = source is ArchiveSource
    val containerFile: File
        get() = when (val s = source) {
            is FileSource -> s.file
            is ArchiveSource -> s.archiveFile
            else -> throw IllegalStateException("unknown source")
        }
    val displayPath: String
        get() = when (val s = source) {
            is FileSource -> s.file.absolutePath
            is ArchiveSource -> "${s.archiveFile.absolutePath}!/${s.entryName}"
            else -> ""
        }

    override fun equals(other: Any?): Boolean =
        other is ImageItem && other.source.cacheKey == source.cacheKey

    override fun hashCode(): Int = source.cacheKey.hashCode()
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/**
 * 按文件名过滤（大小写不敏感，忽略首尾空白）；[query] 为空时返回原列表。
 * 网格显示（GalleryScreen）与查看器翻页（AppState.step）都走这里，保证两者顺序一致。
 */
fun filterImages(images: List<ImageItem>, query: String): List<ImageItem> {
    val q = query.trim()
    return if (q.isEmpty()) images else images.filter { it.name.contains(q, ignoreCase = true) }
}

/**
 * 自然排序比较：数字段按数值比较，其余按字符（忽略大小写）。
 * `2.jpg` < `10.jpg`，`第9话` < `第10话` —— 漫画 / 扫描图的文件名几乎都依赖这一点，
 * 纯字典序会把第 10 页排到第 2 页前面。数值相等时（`01` 与 `1`）退回原始字符串比较，保证全序。
 *
 * 数字段只认 ASCII `0-9`：`Char.isDigit()` 是 Unicode Nd（全角 `１`、阿拉伯-印度数字 `٩`…），
 * 这些字符一边按「数值」、一边按码点和字母比较，两套规则混用会破坏传递性
 * （`11. < a < ٩b` 却 `٩b < 11.`），排序结果随输入顺序变化。全角数字因此按普通字符排。
 */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
            val si = i
            val sj = j
            while (i < a.length && a[i].isAsciiDigit()) i++
            while (j < b.length && b[j].isAsciiDigit()) j++
            // 去掉前导零后先比位数、再逐位比，避免超长数字溢出 Long。
            val na = a.substring(si, i).trimStart('0')
            val nb = b.substring(sj, j).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    val rest = (a.length - i) - (b.length - j)
    return if (rest != 0) rest else a.compareTo(b)
}

private fun Char.isAsciiDigit() = this in '0'..'9'

val NaturalOrder: Comparator<String> = Comparator(::naturalCompare)

fun sortImages(list: List<ImageItem>, mode: SortMode, direction: SortDirection): List<ImageItem> {
    val base = when (mode) {
        SortMode.NAME -> list.sortedWith(compareBy(NaturalOrder) { it.name })
        SortMode.DATE -> list.sortedBy { it.modified }
        SortMode.SIZE -> list.sortedBy { it.sizeBytes }
    }
    return if (direction == SortDirection.DESC) base.reversed() else base
}
