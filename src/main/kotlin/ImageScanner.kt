package gallery

import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ImageScanner {
    // 只列 skiko 真能解码的格式：它没有编进 AVIF / HEIF 解码器，列进来只会得到一张张「坏图」。
    val EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "ico", "jfif")

    /** 并行遍历子目录的 worker 数：瓶颈在磁盘，开太多反而更慢。 */
    private const val SCAN_WORKERS = 8

    /** 单次扫描最多收集的图片数；达到上限时调用方应提示用户列表被截断了。 */
    const val MAX_FILES = 20_000

    suspend fun scan(folder: File, recursive: Boolean, maxFiles: Int = MAX_FILES): List<ImageItem> =
        withContext(Dispatchers.IO) {
            val found = Collections.synchronizedList(ArrayList<ImageItem>())
            if (!recursive) {
                collectImages(folder, found, maxFiles)
                return@withContext found.take(maxFiles)
            }

            // 递归目录树由多个 worker 并行遍历：单个大目录（上万文件）能明显缩短等待时间。
            // pending = 已入队但尚未处理完的目录数，归零时关闭队列让 worker 退出。
            val queue = Channel<File>(Channel.UNLIMITED)
            val pending = AtomicInteger(1)
            queue.trySend(folder)
            coroutineScope {
                repeat(SCAN_WORKERS) {
                    launch {
                        for (dir in queue) {
                            // 用户已切到别的图集时尽快停下，不再白白遍历整棵目录树。
                            ensureActive()
                            if (found.size < maxFiles) {
                                val children = dir.listFiles()
                                if (children != null) {
                                    for (f in children) {
                                        when {
                                            // 不跟随符号链接目录：避免目录环导致递归无限展开，
                                            // 也与多数扫描工具（Files.walk 默认不跟随）保持一致。
                                            f.isDirectory && !java.nio.file.Files.isSymbolicLink(f.toPath()) -> {
                                                pending.incrementAndGet()
                                                queue.trySend(f)
                                            }
                                            f.isFile && f.extension.lowercase() in EXTENSIONS && !isMacMetadata(f.name) ->
                                                if (found.size < maxFiles) {
                                                    found += fileItem(f)
                                                }
                                        }
                                    }
                                }
                            }
                            if (pending.decrementAndGet() == 0) queue.close()
                        }
                    }
                }
            }
            found.take(maxFiles)
        }

    /**
     * macOS 打包 / 拷贝时附带的元数据文件：`__MACOSX/` 目录下的一切，以及 AppleDouble 的 `._xxx.jpg`。
     * 它们扩展名是 .jpg 却不是图片，混进列表就是一张张「坏图」。[path] 可以是 `/` 分隔的条目名。
     */
    fun isMacMetadata(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.startsWith("__MACOSX/") || normalized.contains("/__MACOSX/") ||
            normalized.substringAfterLast('/').startsWith("._")
    }

    private fun fileItem(f: File): ImageItem {
        val size = f.length()
        val modified = f.lastModified()
        return ImageItem(FileSource(f, size, modified), f.name, size, modified)
    }

    private fun collectImages(dir: File, result: MutableList<ImageItem>, maxFiles: Int) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (result.size >= maxFiles) return
            if (f.isFile && f.extension.lowercase() in EXTENSIONS && !isMacMetadata(f.name)) {
                result += fileItem(f)
            }
        }
    }

    suspend fun scanArchive(archiveFile: File, reader: ArchiveReader): List<ImageItem> =
        withContext(Dispatchers.IO) {
            reader.entries
                .filter {
                    !it.isDirectory && !isMacMetadata(it.name) &&
                        it.name.substringAfterLast('/').substringAfterLast('.', "").lowercase() in EXTENSIONS
                }
                .map { e ->
                    val displayName = e.name.substringAfterLast('/')
                    ImageItem(
                        ArchiveSource(archiveFile, e.name, reader, e.size, e.modified),
                        displayName,
                        e.size,
                        e.modified,
                    )
                }
                .sortedWith(compareBy(NaturalOrder) { it.name })
        }
}
