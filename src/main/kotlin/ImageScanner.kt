package gallery

import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ImageScanner {
    val EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "ico", "jfif", "avif")

    /** 并行遍历子目录的 worker 数：瓶颈在磁盘，开太多反而更慢。 */
    private const val SCAN_WORKERS = 8

    suspend fun scan(folder: File, recursive: Boolean, maxFiles: Int = 20_000): List<ImageItem> =
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
                                            f.isFile && f.extension.lowercase() in EXTENSIONS ->
                                                if (found.size < maxFiles) {
                                                    found += ImageItem(FileSource(f), f.name, f.length(), f.lastModified())
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

    private fun collectImages(dir: File, result: MutableList<ImageItem>, maxFiles: Int) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (result.size >= maxFiles) return
            if (f.isFile && f.extension.lowercase() in EXTENSIONS) {
                result += ImageItem(FileSource(f), f.name, f.length(), f.lastModified())
            }
        }
    }

    suspend fun scanArchive(archiveFile: File, reader: ArchiveReader): List<ImageItem> =
        withContext(Dispatchers.IO) {
            reader.entries
                .filter {
                    !it.isDirectory &&
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
                .sortedBy { it.name.lowercase() }
        }
}
