package gallery

import java.awt.Component
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
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
suspend fun pickFolder(parent: Component?, title: String): File? = withContext(Dispatchers.Swing) {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = title
    }
    if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** 选择压缩包；[parent] 含义同 [pickFolder]，[filterDescription] 是文件类型下拉框里的文字。 */
suspend fun pickArchive(parent: Component?, title: String, filterDescription: String): File? = withContext(Dispatchers.Swing) {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.FILES_ONLY
        dialogTitle = title
        fileFilter = object : javax.swing.filechooser.FileFilter() {
            override fun accept(f: File): Boolean = f.isDirectory || ArchiveReader.isSupported(f)
            override fun getDescription(): String = filterDescription
        }
    }
    if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** Move to OS recycle bin when possible; returns false if unsupported. */
fun moveToTrash(file: File): Boolean = try {
    Desktop.isDesktopSupported() && Desktop.getDesktop().moveToTrash(file)
} catch (t: Throwable) {
    AppLog.warn("移入回收站失败：${file.absolutePath}（${t.message}）")
    false
}

/**
 * 在文件管理器里显示并**选中** [file]：Windows 用 `explorer /select,`，macOS 用 `open -R`，
 * Linux 走 freedesktop 的 FileManager1.ShowItems（Nautilus / Dolphin / Nemo 等都实现了）。
 * 都不行时退回「打开所在文件夹」。
 */
fun openInFileManager(file: File) {
    val path = file.absolutePath
    val os = System.getProperty("os.name").lowercase()
    val command = when {
        os.startsWith("windows") -> listOf("explorer.exe", "/select,", path)
        os.startsWith("mac") -> listOf("open", "-R", path)
        else -> listOf(
            "dbus-send", "--session", "--print-reply", "--dest=org.freedesktop.FileManager1",
            "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.ShowItems",
            "array:string:${file.toURI()}", "string:",
        )
    }
    try {
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        // explorer.exe 即使成功也常返回 1，不能按退出码判断；其余平台失败时退回打开文件夹。
        if (!os.startsWith("windows") && proc.waitFor() != 0) openFolderOf(file)
    } catch (t: Throwable) {
        AppLog.warn("在文件管理器中定位失败：$path（${t.message}），改为打开所在文件夹")
        openFolderOf(file)
    }
}

private fun openFolderOf(file: File) {
    try {
        Desktop.getDesktop().open(file.parentFile ?: file)
    } catch (t: Throwable) {
        AppLog.warn("打开文件管理器失败：${file.absolutePath}", t)
    }
}

fun openWithDefaultApp(file: File) {
    try {
        Desktop.getDesktop().open(file)
    } catch (t: Throwable) {
        AppLog.warn("用默认程序打开失败：${file.absolutePath}", t)
    }
}

/**
 * 用系统默认程序打开 [item]。压缩包里的条目先解到临时目录（退出时删除）再打开，
 * 外部程序没法直接读压缩包内部。
 */
fun openItemWithDefaultApp(item: ImageItem) {
    val file = item.file ?: try {
        val dir = File(System.getProperty("java.io.tmpdir"), "ComposeGallery").apply { mkdirs() }
        File.createTempFile("entry-", "-" + item.name, dir).apply {
            writeBytes(item.source.openBytes())
            deleteOnExit()
        }
    } catch (t: Throwable) {
        AppLog.warn("解出压缩包条目失败：${item.displayPath}", t)
        return
    }
    openWithDefaultApp(file)
}

/** 把图片放进系统剪贴板；失败（无剪贴板 / 无头环境）时返回 false。 */
fun copyImageToClipboard(image: java.awt.Image): Boolean = try {
    val selection = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
            return image
        }
    }
    Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
    true
} catch (t: Throwable) {
    AppLog.warn("复制到剪贴板失败", t)
    false
}
