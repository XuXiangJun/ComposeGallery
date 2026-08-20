package gallery

import com.google.gson.GsonBuilder
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
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val listType = object : TypeToken<List<BookEntry>>() {}.type

    val dir: File = File(System.getProperty("user.home"), ".ComposeGallery")
    val file: File = File(dir, "bookshelf.json")

    fun load(): List<BookEntry> = try {
        if (!file.exists()) emptyList()
        else gson.fromJson(file.readText(), listType) ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    fun save(books: List<BookEntry>) {
        try {
            dir.mkdirs()
            file.writeText(gson.toJson(books))
        } catch (_: Throwable) {
            // 沙箱或权限问题下静默失败（书架仅本次会话有效）
        }
    }
}
