package gallery

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile as CompressZipFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
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
                zip.getInputStream(e).use { readBounded(it, name) }
            }
            sevenZ != null -> {
                val e = sevenZEntryByName[name] ?: throw NoSuchElementException("7z entry: $name")
                sevenZ.getInputStream(e).use { readBounded(it, name) }
            }
            else -> throw IllegalStateException("no archive loaded")
        }
        cachePut(name, bytes)
        return bytes
    }

    /**
     * 读条目内容，但最多 [MAX_ENTRY_BYTES]。条目头里声明的大小不可信（压缩炸弹可以谎报成
     * 几 KB、解出来几个 GB），所以按实际读到的字节数截断，超限直接失败，而不是把进程撑爆。
     */
    private fun readBounded(input: InputStream, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) throw IOException("条目过大（超过 ${MAX_ENTRY_BYTES / 1024 / 1024} MB）：$name")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
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
        // cbz / cb7 是漫画阅读器通行的扩展名，内容就是普通的 zip / 7z。
        val SUPPORTED_EXTENSIONS = setOf("zip", "cbz", "7z", "cb7")

        private const val ENTRY_CACHE_COUNT = 8
        private const val ENTRY_CACHE_BYTES = 32L * 1024 * 1024

        /** 单个条目解压后的上限；再大的单张图片也用不到这么多（见 [readBounded]）。 */
        private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024

        fun isSupported(file: File): Boolean = file.extension.lowercase() in SUPPORTED_EXTENSIONS

        /**
         * 打开压缩包。[password] 只对 7z 有效。
         *
         * @throws PasswordRequiredException 7z 加了密而没给密码（调用方应向用户要密码后重试）。
         * @throws EncryptedZipException zip 条目加了密：commons-compress 不支持解密 zip。
         */
        fun open(file: File, password: CharArray? = null): ArchiveReader = when (file.extension.lowercase()) {
            "zip", "cbz" -> openZip(file).also { reader ->
                if (reader.zipEntries.any { it.generalPurposeBit.usesEncryption() }) {
                    reader.close()
                    throw EncryptedZipException(file.name)
                }
            }
            "7z", "cb7" -> openSevenZ(file, password)
            else -> throw IllegalArgumentException("不支持的压缩格式: ${file.extension}")
        }

        private fun openSevenZ(file: File, password: CharArray?): ArchiveReader {
            val builder = SevenZFile.builder().setFile(file)
            if (password != null) builder.setPassword(password)
            // 连文件名一起加密的 7z 在这里就会抛 PasswordRequiredException。
            val reader = ArchiveReader(null, builder.get())
            // 只加密内容、不加密文件名的 7z 能打开、能列目录，要到读条目时才失败 ——
            // 那样每张缩略图都各自失败一次。提前按压缩方法识别出来，统一走要密码的流程。
            if (password == null && reader.sevenZEntries.any { e ->
                    e.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true
                }
            ) {
                reader.close()
                throw PasswordRequiredException(file.name)
            }
            return reader
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
            val charset = if (looksLikeShiftJis(utf8)) Charset.forName("windows-31j") else Charset.forName("GB18030")
            utf8.close()
            val fallback = CompressZipFile.builder()
                .setFile(file)
                .setCharset(charset)
                .get()
            return ArchiveReader(fallback, null)
        }

        /**
         * 非 UTF-8 的条目名是不是 Shift-JIS（日文压缩包）。
         *
         * 不能只看「能不能严格按 Shift_JIS 解码」：GBK 的大量字节序列在 Shift_JIS 里也合法
         * （0xA1–0xDF 是 Shift_JIS 的半角片假名）。但真正的日文文件名几乎总带全角平假名 /
         * 片假名（U+3040–U+30FF），而 GBK 字节按 Shift_JIS 解出来落在半角片假名区
         * （U+FF61–U+FF9F），不会出现全角假名。所以要求：全部严格可解 + 至少出现一个全角假名。
         *
         * 探测和回退都用 windows-31j（CP932）而不是严格的 `Shift_JIS`：Windows 上压出来的日文
         * 压缩包实际是 CP932，`①`、`㈱`、`髙` 这类 NEC / IBM 扩展字严格 Shift_JIS 解不了 ——
         * 一个这样的字符就会让整包被判成「不是日文」、改按 GB18030 打开，所有条目名乱码。
         */
        private fun looksLikeShiftJis(zip: CompressZipFile): Boolean {
            val sjis = Charset.forName("windows-31j")
            var sawKana = false
            for (e in zip.entries.toList()) {
                val raw = e.rawName ?: continue
                if (isValidUtf8(raw)) continue
                val decoded = try {
                    sjis.newDecoder().decode(ByteBuffer.wrap(raw)).toString()
                } catch (_: CharacterCodingException) {
                    return false
                }
                if (decoded.any { it in '\u3040'..'\u30FF' }) sawKana = true
            }
            return sawKana
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

/** zip 里有加密条目。commons-compress 不能解密 zip（ZipCrypto / AES 都不行），只能提示用户。 */
class EncryptedZipException(name: String) : IOException("加密的 zip 暂不支持：$name")
