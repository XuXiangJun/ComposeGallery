package gallery

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import gallery.ui.App

fun main() = application {
    val appState = remember { AppState() }
    val windowState = rememberWindowState(width = 1320.dp, height = 860.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "Compose Gallery",
        state = windowState,
    ) {
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
