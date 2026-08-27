package gallery

import java.io.File

enum class SortMode(val label: String) {
    NAME("名称"),
    DATE("修改时间"),
    SIZE("大小"),
}

/** 图片字节来源：磁盘文件或压缩包内条目。 */
interface ImageSource {
    val cacheKey: String
    fun openBytes(): ByteArray
}

class FileSource(val file: File) : ImageSource {
    override val cacheKey: String
        get() = "file:${file.absolutePath}@${file.lastModified()}:${file.length()}"
    override fun openBytes(): ByteArray = file.readBytes()
}

class ArchiveSource(
    val archiveFile: File,
    val entryName: String,
    val reader: ArchiveReader,
    val size: Long,
    val modified: Long,
) : ImageSource {
    override val cacheKey: String
        get() = "arc:${archiveFile.absolutePath}!/$entryName@$modified:$size"
    override fun openBytes(): ByteArray = reader.readEntry(entryName)
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

fun sortImages(list: List<ImageItem>, mode: SortMode, direction: SortDirection): List<ImageItem> {
    val base = when (mode) {
        SortMode.NAME -> list.sortedBy { it.name.lowercase() }
        SortMode.DATE -> list.sortedBy { it.modified }
        SortMode.SIZE -> list.sortedBy { it.sizeBytes }
    }
    return if (direction == SortDirection.DESC) base.reversed() else base
}
