package gallery

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile as CompressZipFile
import java.io.File
import java.nio.charset.Charset

/**
 * 读取压缩包内条目的统一封装。
 *
 * - zip 用 Apache Commons Compress（比 JDK 的 ZipFile 宽容，能打开 GBK/Shift-JIS 等
 *   非标准编码条目名的压缩包——JDK 对这类条目会抛 "invalid CEN header"）。
 * - 7z 用 Commons Compress 的 SevenZFile。
 */
class ArchiveReader private constructor(
    private val zip: CompressZipFile?,
    private val sevenZ: SevenZFile?,
) : AutoCloseable {

    data class Entry(
        val name: String,
        val size: Long,
        val modified: Long,
        val isDirectory: Boolean,
    )

    private val zipEntryByName: Map<String, ZipArchiveEntry>? =
        zip?.entries?.toList()?.associateBy { it.name }

    private val sevenZEntryByName: Map<String, SevenZArchiveEntry>? =
        sevenZ?.entries?.associateBy { it.name }

    val entries: List<Entry> = buildList {
        when {
            zip != null -> zip.entries.toList().forEach {
                add(Entry(it.name, it.size, it.lastModifiedDate?.time ?: 0L, it.isDirectory))
            }
            sevenZ != null -> sevenZ.entries.forEach {
                add(Entry(it.name, it.size, it.lastModifiedDate?.time ?: 0L, it.isDirectory))
            }
        }
    }

    @Synchronized
    fun readEntry(name: String): ByteArray = when {
        zip != null -> {
            val e = zipEntryByName?.get(name) ?: throw NoSuchElementException("zip entry: $name")
            zip.getInputStream(e).use { it.readBytes() }
        }
        sevenZ != null -> {
            val e = sevenZEntryByName?.get(name) ?: throw NoSuchElementException("7z entry: $name")
            sevenZ.getInputStream(e).use { it.readBytes() }
        }
        else -> throw IllegalStateException("no archive loaded")
    }

    override fun close() {
        try { zip?.close() } catch (_: Throwable) {}
        try { sevenZ?.close() } catch (_: Throwable) {}
    }

    companion object {
        val SUPPORTED_EXTENSIONS = setOf("zip", "7z")

        fun isSupported(file: File): Boolean = file.extension.lowercase() in SUPPORTED_EXTENSIONS

        fun open(file: File): ArchiveReader = when (file.extension.lowercase()) {
            "zip" -> openZip(file)
            "7z" -> ArchiveReader(null, SevenZFile.builder().setFile(file).get())
            else -> throw IllegalArgumentException("不支持的压缩格式: ${file.extension}")
        }

        private fun openZip(file: File): ArchiveReader {
            val utf8 = CompressZipFile.builder()
                .setFile(file)
                .setCharset(Charsets.UTF_8)
                .get()
            // 若条目名含替换字符，说明编码不是 UTF-8（常见为 GBK/GB18030 中文名），换编码重开。
            val needsFallback = utf8.entries.toList().any { '\uFFFD' in it.name }
            if (!needsFallback) {
                return ArchiveReader(utf8, null)
            }
            utf8.close()
            val gbk = CompressZipFile.builder()
                .setFile(file)
                .setCharset(Charset.forName("GB18030"))
                .get()
            return ArchiveReader(gbk, null)
        }
    }
}
