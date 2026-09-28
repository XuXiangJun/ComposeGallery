package gallery

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import gallery.i18n.Strings
import gallery.i18n.StringsKey
import gallery.ui.App
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.UIManager
import org.jetbrains.skia.Image

fun main(args: Array<String>) {
    // 文件选择框（JFileChooser）默认是 Swing 的 Metal 外观，和系统格格不入；换成系统外观。
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    // 第一个参数是要打开的路径（文件夹 / 压缩包 / 图片），「打开方式」和文件关联都走这里。
    runApp(args.firstOrNull()?.let(::File))
}

private fun runApp(initialPath: File?) = application {
    val appState = remember { AppState().also { it.pendingOpen = initialPath } }
    val windowState = remember { restoreWindowState(appState.initialWindow) }
    val appTitle = Strings(appState.localeTag).t(StringsKey.AppTitle)
    Window(
        onCloseRequest = {
            // 翻页进度是延迟 0.8s 合并写盘的（见 App 里的 LaunchedEffect(selectedIndex)），
            // 翻完页立刻关窗口时那一笔还没落盘，这里补存一次。
            appState.saveCurrentProgress()
            appState.saveWindow(windowState.toGeometry(appState.initialWindow))
            appState.archiveReader?.close()
            exitApplication()
        },
        // 标题栏带上当前图集名，任务栏里多开时也分得清。
        title = appState.locationName?.let { "$it - $appTitle" } ?: appTitle,
        state = windowState,
    ) {
        val window = this.window
        // 设置窗口标题栏左上角 / 任务栏图标（打包时的 jpackage --icon 只作用于 exe/msi，不会嵌入运行时标题栏）。
        LaunchedEffect(Unit) {
            val awtIcon = runCatching {
                val bytes = object {}.javaClass.getResourceAsStream("/icon.ico")?.readBytes()
                    ?: return@runCatching null
                Image.makeFromEncoded(bytes).toComposeImageBitmap().toAwtImage()
            }.getOrNull() ?: return@LaunchedEffect
            window.iconImages = listOf(awtIcon)
        }
        App(
            state = appState,
            onToggleFullscreen = {
                windowState.placement =
                    if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating
                    else WindowPlacement.Fullscreen
            },
            // ComposeWindow 就是 AWT Component：作为文件对话框的 parent，
            // 让它附着在主窗口上而不是变成孤立的顶层窗口。
            dialogParent = window,
            isFullscreen = windowState.placement == WindowPlacement.Fullscreen,
        )
    }
}

private fun restoreWindowState(g: WindowGeometry): WindowState {
    val size = DpSize(g.width.dp, g.height.dp)
    // 上次的位置可能落在已经拔掉的显示器上：不在任何屏幕内就交给系统居中。
    val x = g.x
    val y = g.y
    val onScreen = x != null && y != null && GraphicsEnvironment.getLocalGraphicsEnvironment()
        .screenDevices.any { it.defaultConfiguration.bounds.contains(x.toInt() + 40, y.toInt() + 20) }
    return WindowState(
        placement = if (g.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = if (onScreen && x != null && y != null) WindowPosition(x.dp, y.dp) else WindowPosition.PlatformDefault,
        size = size,
    )
}

/**
 * 当前窗口几何；最大化 / 全屏时 size 和 position 是铺满屏幕的值，不能当成「还原后」的大小存下来，
 * 所以只记 maximized 标记，尺寸沿用上一次的浮动窗口值。
 */
private fun WindowState.toGeometry(previous: WindowGeometry): WindowGeometry {
    if (placement != WindowPlacement.Floating) return previous.copy(maximized = placement == WindowPlacement.Maximized)
    val pos = position
    return WindowGeometry(
        x = if (pos.isSpecified) pos.x.value else null,
        y = if (pos.isSpecified) pos.y.value else null,
        width = size.width.value,
        height = size.height.value,
        maximized = false,
    )
}
