package gallery

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 书架 / 设置的公共持久化层：数据目录、JSON 编解码、原子写。
 *
 * 之所以不直接 [File.writeText]：它是「先截断目标文件、再写入」，一旦中途崩溃或断电，
 * 磁盘上就留着半个 JSON。而两个 Store 的 load() 遇到坏文件只会返回空值 —— 用户的书架和
 * 设置就这么静默丢了，且没有任何提示。所以 save() 一律走
 * 「同目录建临时文件 → move 覆盖」：同分区下 move 是原子的，最坏情况只是多一个残留的
 * `.tmp`，不会损坏真正的数据文件。
 */
internal object JsonStore {
    val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * 数据目录，默认 `~/.ComposeGallery`。
     *
     * 可用 `-Dcompose.gallery.home=<dir>` 整体指到别处：单元测试靠它把读写隔离到临时目录
     * （否则测试会碰真实的用户数据），便携版也能用它把配置跟着 U 盘走。
     */
    val dir: File = File(
        System.getProperty("compose.gallery.home")
            ?: File(System.getProperty("user.home"), ".ComposeGallery").absolutePath
    )

    fun file(fileName: String): File = File(dir, fileName)

    /** 读 [fileName] 的原始文本；文件不存在或不可读时返回 null（调用方各自决定兜底值）。 */
    fun read(fileName: String): String? = try {
        val f = file(fileName)
        if (f.exists()) f.readText() else null
    } catch (_: Throwable) {
        null
    }

    /** 原子写入 [fileName]。失败时不抛到调用方（原有的静默语义），但会清理残留的临时文件。 */
    fun write(fileName: String, text: String) {
        try {
            dir.mkdirs()
        } catch (_: Throwable) {
            return
        }
        // 临时文件建在目标同目录：move 只有在同一文件系统上才可能原子。
        val tmp = try {
            Files.createTempFile(dir.toPath(), fileName, ".tmp").toFile()
        } catch (_: Throwable) {
            return
        }
        val target = file(fileName)
        try {
            tmp.writeText(text)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // 少数文件系统（部分网络盘 / FUSE）不支持原子 move，退化成普通覆盖写。
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Throwable) {
                tmp.delete()
            }
        } catch (_: Throwable) {
            tmp.delete()
        }
    }
}
