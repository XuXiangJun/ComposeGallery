package gallery

import java.awt.Component
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * 选择图片文件夹。
 *
 * [parent] 传主窗口（Compose Desktop 的 ComposeWindow 就是 AWT Component）：对话框才会
 * 附着在主窗口上居中显示 —— 传 null 时它可能跑到任务栏、或出现在另一块屏幕后面。
 */
suspend fun pickFolder(parent: Component?): File? = withContext(Dispatchers.Swing) {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "选择图片文件夹"
    }
    if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** 选择压缩包；[parent] 含义同 [pickFolder]。 */
suspend fun pickArchive(parent: Component?): File? = withContext(Dispatchers.Swing) {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.FILES_ONLY
        dialogTitle = "选择压缩包（zip / 7z）"
        fileFilter = object : javax.swing.filechooser.FileFilter() {
            override fun accept(f: File): Boolean = f.isDirectory || ArchiveReader.isSupported(f)
            override fun getDescription(): String = "压缩包 (*.zip, *.7z)"
        }
    }
    if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** Move to OS recycle bin when possible; returns false if unsupported. */
fun moveToTrash(file: File): Boolean = try {
    Desktop.isDesktopSupported() && Desktop.getDesktop().moveToTrash(file)
} catch (_: Throwable) {
    false
}

fun openInFileManager(file: File) {
    try {
        Desktop.getDesktop().open(file.parentFile ?: file)
    } catch (_: Throwable) {
    }
}

fun openWithDefaultApp(file: File) {
    try {
        Desktop.getDesktop().open(file)
    } catch (_: Throwable) {
    }
}
