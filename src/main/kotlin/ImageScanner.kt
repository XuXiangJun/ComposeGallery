package gallery

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageScanner {
    val EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "ico", "jfif", "avif")

    suspend fun scan(folder: File, recursive: Boolean, maxFiles: Int = 20_000): List<ImageItem> =
        withContext(Dispatchers.IO) {
            val result = ArrayList<ImageItem>()
            fun walk(dir: File) {
                if (result.size >= maxFiles) return
                val children = dir.listFiles() ?: return
                for (f in children) {
                    if (result.size >= maxFiles) return
                    when {
                        f.isDirectory && recursive -> walk(f)
                        f.isFile && f.extension.lowercase() in EXTENSIONS ->
                            result += ImageItem(FileSource(f), f.name, f.length(), f.lastModified())
                    }
                }
            }
            walk(folder)
            result
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
