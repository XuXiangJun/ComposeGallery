package gallery

import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 书架上的一本书（一个图集：文件夹或压缩包）。
 *
 * @param cover 封面图来源：普通文件用其绝对路径；压缩包条目用 "归档路径!/条目名"。
 */
data class BookEntry(
    val id: String,
    val name: String,
    val type: String, // "folder" | "archive"
    val path: String,
    val cover: String,
    val progress: Int,
    val total: Int,
    val lastRead: Long,
)

object BookshelfStore {
    private val listType = object : TypeToken<List<BookEntry>>() {}.type

    val dir: File get() = JsonStore.dir
    val file: File get() = JsonStore.file("bookshelf.json")

    fun load(): List<BookEntry> {
        val text = JsonStore.read("bookshelf.json") ?: return emptyList()
        return try {
            JsonStore.gson.fromJson(text, listType) ?: emptyList()
        } catch (_: Throwable) {
            // 文件损坏时退化成空书架，而不是让应用起不来。
            emptyList()
        }
    }

    fun save(books: List<BookEntry>) = JsonStore.write("bookshelf.json", JsonStore.gson.toJson(books))
}
