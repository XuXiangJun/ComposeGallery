package gallery

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 极简文件日志：`<数据目录>/logs/gallery.log`，超过 1MB 滚动成 `gallery.log.1`。
 *
 * 应用里大量失败是「静默降级」的（解码失败显示占位、存盘失败不打扰用户），这本身没错，
 * 但一点痕迹都不留的话，用户反馈「某张图打不开 / 书架丢了」时无从排查。
 * 日志本身写失败一律忽略：它不能成为新的故障来源。
 */
object AppLog {
    private const val MAX_BYTES = 1L * 1024 * 1024
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    val file: File get() = File(JsonStore.dir, "logs/gallery.log")

    fun warn(message: String, t: Throwable? = null) = write("WARN", message, t)

    fun error(message: String, t: Throwable? = null) = write("ERROR", message, t)

    @Synchronized
    private fun write(level: String, message: String, t: Throwable?) {
        try {
            val f = file
            f.parentFile?.mkdirs()
            if (f.length() > MAX_BYTES) {
                val rolled = File(f.parentFile, "${f.name}.1")
                rolled.delete()
                f.renameTo(rolled)
            }
            val sb = StringBuilder()
            sb.append(LocalDateTime.now().format(timeFormat)).append(' ').append(level).append(' ')
                .append(message).append('\n')
            if (t != null) {
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                sb.append(sw)
            }
            f.appendText(sb.toString())
        } catch (_: Throwable) {
        }
    }
}
