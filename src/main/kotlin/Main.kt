package gallery

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import gallery.ui.App
import org.jetbrains.skia.Image

fun main() = application {
    val appState = remember { AppState() }
    val windowState = rememberWindowState(width = 1320.dp, height = 860.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "Compose Gallery",
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
        )
    }
}
