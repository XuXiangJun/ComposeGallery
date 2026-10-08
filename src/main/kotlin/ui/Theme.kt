package gallery.ui

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 查看器（恒定深色，不随主题）使用的强调色。 */
val Accent = Color(0xFF4FC3F7)

/**
 * 语义色 token：整个应用只通过 [GalleryColors] 取色，禁止在组件里散写十六进制颜色，
 * 以便支持光暗主题切换并保证对比度一致。
 */
data class GalleryColors(
    val background: Color,
    val surface: Color,             // 工具栏 / 卡片底
    val surfaceHigh: Color,         // 略亮的卡片 / 输入底
    val surfaceDim: Color,          // 更暗的次要区域
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,    // 次要文字
    val onSurfaceMuted: Color,      // 三级文字（保证正文对比度 ≥ AA）
    val primary: Color,
    val onPrimary: Color,           // primary 上的文字色，保证对比度
    val secondary: Color,
    val onSecondary: Color,
    val outline: Color,             // 默认边框
    val outlineHover: Color,        // hover 边框
    val danger: Color,
    val onDanger: Color,            // danger 上的文字色，保证对比度
    val placeholder: Color,         // 缩略图 / 占位底
)

/** 查看器（看图）始终使用深色环境，与主题无关，避免影响图片呈现。 */
val ViewerScrim = Color(0xCC000000)
val ViewerPanelScrim = Color(0xE6000000)

// Viewer 内固定色值（不随主题切换）：中灰用于次要文字 / 占位。
val ViewerMuted = Color(0xFFAAAAAA)
val ViewerSubtle = Color(0xFFBBBBBB)
val ViewerCaption = Color(0xFF999999)
val ViewerBody = Color(0xFFEEEEEE)
val ViewerDim = Color(0xFF666666)
val ViewerIcon = Color.White

val DarkGalleryColors = GalleryColors(
    background = Color(0xFF101010),
    surface = Color(0xFF1C1C1E),
    surfaceHigh = Color(0xFF232326),
    surfaceDim = Color(0xFF2A2A2D),
    onBackground = Color(0xFFE6E6E6),
    onSurface = Color(0xFFE6E6E6),
    onSurfaceVariant = Color(0xFFB8B8B8),
    onSurfaceMuted = Color(0xFF9A9A9A),
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF0A2733),
    secondary = Color(0xFF4FC3F7),
    onSecondary = Color(0xFF0A2733),
    outline = Color(0xFF3A3A3C),
    outlineHover = Color(0xFF4FC3F7),
    danger = Color(0xFFE57373),
    onDanger = Color(0xFF2B0A0A), // 白字压在 #E57373 上只有 3.0:1，深字 6.1:1
    placeholder = Color(0xFF2A2A2D),
)

val LightGalleryColors = GalleryColors(
    background = Color(0xFFF4F5F7),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFECEDEF),
    surfaceDim = Color(0xFFE2E3E6),
    onBackground = Color(0xFF1A1A1C),
    onSurface = Color(0xFF1A1A1C),
    onSurfaceVariant = Color(0xFF4A4A4E),
    onSurfaceMuted = Color(0xFF64646A),
    primary = Color(0xFF0277BD),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF0277BD),
    onSecondary = Color(0xFFFFFFFF),
    outline = Color(0xFFCFCFD2),
    outlineHover = Color(0xFF0277BD),
    danger = Color(0xFFC62828),
    onDanger = Color(0xFFFFFFFF),
    placeholder = Color(0xFFE2E3E6),
)

val LocalGalleryColors = staticCompositionLocalOf { DarkGalleryColors }

@Composable
fun GalleryTheme(isDark: Boolean, content: @Composable () -> Unit) {
    val colors = if (isDark) DarkGalleryColors else LightGalleryColors
    val materialColors = if (isDark) {
        darkColors(
            primary = colors.primary,
            primaryVariant = colors.primary,
            secondary = colors.secondary,
            background = colors.background,
            surface = colors.surface,
            onPrimary = colors.onPrimary,
            onBackground = colors.onBackground,
            onSurface = colors.onSurface,
            error = colors.danger,
        )
    } else {
        lightColors(
            primary = colors.primary,
            primaryVariant = colors.primary,
            secondary = colors.secondary,
            background = colors.background,
            surface = colors.surface,
            onPrimary = colors.onPrimary,
            onBackground = colors.onBackground,
            onSurface = colors.onSurface,
            error = colors.danger,
        )
    }
    CompositionLocalProvider(LocalGalleryColors provides colors) {
        MaterialTheme(colors = materialColors, content = content)
    }
}

/** 布局 token：统一间距 / 圆角 / 字号，避免各界面散落魔法值。 */
object GalleryTokens {
    // 间距
    val spacingXs = 4.dp
    val spacingS = 8.dp
    val spacingM = 12.dp
    val spacingL = 16.dp
    val spacingXl = 24.dp

    // 圆角
    val shapeS = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    val shapeM = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)

    // 字号（caption < small < body < base < title < headline）
    val textCaption = 11.sp
    val textSmall = 12.sp
    val textBody = 13.sp
    val textBase = 14.sp
    val textTitle = 15.sp
    val textHeadline = 18.sp
}
