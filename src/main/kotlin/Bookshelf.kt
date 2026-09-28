package gallery

import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 书架上的一本书（一个图集：文件夹或压缩包）。
 *
 * @param cover 封面图来源：普通文件用其绝对路径；压缩包条目用 "归档路径!/条目名"。
 * @param progressKey 当前阅读那张图的 [ImageItem.displayPath]。恢复进度时优先按它定位，
 *   [progress] 下标只作兜底 —— 换了排序方式、或文件夹里增删了文件后，下标指向的就是另一张图。
 *   旧版本写的 bookshelf.json 没有这个字段，Gson 会读成 null，此时退回按下标恢复。
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
    val progressKey: String? = null,
)

object BookshelfStore {
    private val listType = object : TypeToken<List<BookEntry?>>() {}.type

    val dir: File get() = JsonStore.dir
    val file: File get() = JsonStore.file("bookshelf.json")

    fun load(): List<BookEntry> {
        val text = JsonStore.read("bookshelf.json") ?: return emptyList()
        val parsed: List<BookEntry?> = try {
            JsonStore.gson.fromJson(text, listType) ?: emptyList()
        } catch (t: Throwable) {
            // 文件损坏时退化成空书架，而不是让应用起不来；原文件先留个备份。
            AppLog.warn("解析 bookshelf.json 失败", t)
            JsonStore.backupCorrupt("bookshelf.json")
            return emptyList()
        }
        // 丢掉缺关键字段的条目（手改 / 旧版本写坏的），其余照常加载。
        return parsed.filter { it != null && allPresent(it.id, it.name, it.type, it.path, it.cover) }
            .map { it!! }
    }

    fun save(books: List<BookEntry>) = JsonStore.write("bookshelf.json", JsonStore.gson.toJson(books))
}
