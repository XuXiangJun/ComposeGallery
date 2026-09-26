package gallery

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile as CompressZipFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

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

    // 条目表只遍历一次：原先 entries 与 byName 各遍历一遍，大压缩包白跑一次。
    private val zipEntries: List<ZipArchiveEntry> = zip?.entries?.toList().orEmpty()
    private val zipEntryByName: Map<String, ZipArchiveEntry> = zipEntries.associateBy { it.name }

    private val sevenZEntries: List<SevenZArchiveEntry> = sevenZ?.entries?.toList().orEmpty()
    private val sevenZEntryByName: Map<String, SevenZArchiveEntry> = sevenZEntries.associateBy { it.name }

    val entries: List<Entry> = buildList {
        zipEntries.forEach {
            add(Entry(it.name, it.size, it.lastModifiedDate?.time ?: 0L, it.isDirectory))
        }
        sevenZEntries.forEach {
            add(Entry(it.name, it.size, it.lastModifiedDate?.time ?: 0L, it.isDirectory))
        }
    }

    /**
     * 最近读过的条目字节（LRU）。翻页/缩放会反复读同一张图，zip 每次都要重新解压、
     * 7z 更贵；这里按「条目数 + 总字节」双重上限缓存，避免内存与重复解压两头吃亏。
     */
    private val entryCache = LinkedHashMap<String, ByteArray>(8, 0.75f, true)

    @Synchronized
    fun readEntry(name: String): ByteArray {
        entryCache[name]?.let { return it }
        val bytes = when {
            zip != null -> {
                val e = zipEntryByName[name] ?: throw NoSuchElementException("zip entry: $name")
                zip.getInputStream(e).use { it.readBytes() }
            }
            sevenZ != null -> {
                val e = sevenZEntryByName[name] ?: throw NoSuchElementException("7z entry: $name")
                sevenZ.getInputStream(e).use { it.readBytes() }
            }
            else -> throw IllegalStateException("no archive loaded")
        }
        cachePut(name, bytes)
        return bytes
    }

    private fun cachePut(name: String, bytes: ByteArray) {
        entryCache[name] = bytes
        while ((entryCache.size > ENTRY_CACHE_COUNT || cacheBytes() > ENTRY_CACHE_BYTES) && entryCache.size > 1) {
            val eldest = entryCache.keys.firstOrNull() ?: break
            entryCache.remove(eldest)
        }
    }

    private fun cacheBytes(): Long = entryCache.values.sumOf { it.size.toLong() }

    override fun close() {
        entryCache.clear()
        try { zip?.close() } catch (_: Throwable) {}
        try { sevenZ?.close() } catch (_: Throwable) {}
    }

    companion object {
        val SUPPORTED_EXTENSIONS = setOf("zip", "7z")

        private const val ENTRY_CACHE_COUNT = 8
        private const val ENTRY_CACHE_BYTES = 32L * 1024 * 1024

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
            // 若条目名不是 UTF-8 编码（常见为 GBK/GB18030 中文名），换编码重开。
            if (!needsFallback(utf8)) {
                return ArchiveReader(utf8, null)
            }
            utf8.close()
            val gbk = CompressZipFile.builder()
                .setFile(file)
                .setCharset(Charset.forName("GB18030"))
                .get()
            return ArchiveReader(gbk, null)
        }

        /**
         * 是否有条目名不是 UTF-8 编码的。
         *
         * 不能靠查 `'\uFFFD'` 或 `'?'` 判断（曾经的写法就是查 \uFFFD，永远命中不了）：
         * commons-compress 的 UTF-8 解码器对畸形输入是**替换成 '?'**
         * （NioZipEncoding：CodingErrorAction.REPLACE + replaceWith("?")），既不给 U+FFFD
         * 也不抛异常；而 '?' 本身是合法文件名字符，查它又会把正常 zip 误判成 GBK。
         * 何况畸形字节还可能恰好拼成合法 UTF-8 —— GBK 的 D2 BB 用 UTF-8 解会得到 U+04BB，
         * 光看解码结果根本判断不出原始编码。
         *
         * 可靠做法是拿 [org.apache.commons.compress.archivers.zip.ZipArchiveEntry.getRawName]
         * 的原始字节做一次**严格** UTF-8 解码：抛 CharacterCodingException 就说明这批名字
         * 不是 UTF-8 写的。
         */
        private fun needsFallback(zip: CompressZipFile): Boolean =
            zip.entries.toList().any { !isValidUtf8(it.rawName) }

        /** 严格 UTF-8 解码：任何畸形 / 无法映射的字节都算不合法。 */
        private fun isValidUtf8(bytes: ByteArray?): Boolean {
            if (bytes == null) return true
            return try {
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
                true
            } catch (_: CharacterCodingException) {
                false
            }
        }
    }
}
