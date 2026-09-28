package gallery

import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Locale

// 界面文案统一在 i18n/Strings.kt，枚举里不再各带一份中文 label。
enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class SortDirection { ASC, DESC }

/** 阅读方向：RTL 是日漫习惯，「下一页」在左边（← 键 / 点左侧翻到下一页）。 */
enum class ReadingDirection { LTR, RTL }

/** 查看器里鼠标滚轮的作用；按住 Ctrl 滚动始终是缩放。 */
enum class WheelAction { ZOOM, PAGE }

enum class AppLocale(val tag: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en");

    fun resolved(defaultTag: String): String = if (this == SYSTEM) defaultTag else tag
}

/** 工具栏缩略图大小滑块的范围（dp）；读回持久化值时也按它收口。 */
const val THUMB_SIZE_MIN = 100f
const val THUMB_SIZE_MAX = 360f

/** 「最近打开」的一条记录，type 与 [BookEntry.type] 一致："folder" | "archive"。 */
data class RecentEntry(
    val path: String,
    val name: String,
    val type: String,
    val lastRead: Long,
)

/** 窗口位置与大小（dp）；[x] / [y] 为 null 表示由系统决定位置（居中）。 */
data class WindowGeometry(
    val x: Float? = null,
    val y: Float? = null,
    val width: Float = 1320f,
    val height: Float = 860f,
    val maximized: Boolean = false,
)

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val sortDirection: SortDirection = SortDirection.ASC,
    val slideshowSeconds: Float = 3f,
    val recent: List<RecentEntry> = emptyList(),
    val locale: AppLocale = AppLocale.SYSTEM,
    val thumbSize: Float = 180f,
    val readingDirection: ReadingDirection = ReadingDirection.LTR,
    val wheelAction: WheelAction = WheelAction.ZOOM,
    val sortMode: SortMode = SortMode.NAME,
    val recursive: Boolean = false,
    val window: WindowGeometry = WindowGeometry(),
) {
    companion object {
        fun defaultLocaleTag(): String = if (Locale.getDefault().language == "en") "en" else "zh"
    }
}

/** 把 Gson 填进来的 null / 越界值换回默认值（原因见 [orDefault]）。 */
internal fun Settings.sanitized(): Settings {
    val d = Settings()
    val recentList: List<RecentEntry?> = recent.orDefault(emptyList())
    return Settings(
        themeMode = themeMode.orDefault(d.themeMode),
        sortDirection = sortDirection.orDefault(d.sortDirection),
        slideshowSeconds = slideshowSeconds.takeIf { it in 1f..10f } ?: d.slideshowSeconds,
        recent = recentList.filter { it != null && allPresent(it.path, it.name, it.type) }.map { it!! },
        locale = locale.orDefault(d.locale),
        thumbSize = thumbSize.takeIf { it in THUMB_SIZE_MIN..THUMB_SIZE_MAX } ?: d.thumbSize,
        readingDirection = readingDirection.orDefault(d.readingDirection),
        wheelAction = wheelAction.orDefault(d.wheelAction),
        sortMode = sortMode.orDefault(d.sortMode),
        recursive = recursive,
        window = window.orDefault(d.window).let { w ->
            // 过小（或被写坏）的尺寸退回默认值，免得窗口小到点不着。
            if (w.width < 400f || w.height < 300f) d.window else w
        },
    )
}

object SettingsStore {
    private val type = object : TypeToken<Settings>() {}.type

    val dir: File get() = JsonStore.dir
    val file: File get() = JsonStore.file("settings.json")

    fun load(): Settings {
        val text = JsonStore.read("settings.json") ?: return Settings()
        val parsed: Settings = try {
            JsonStore.gson.fromJson(text, type) ?: Settings()
        } catch (t: Throwable) {
            AppLog.warn("解析 settings.json 失败", t)
            JsonStore.backupCorrupt("settings.json")
            return Settings()
        }
        return parsed.sanitized()
    }

    fun save(settings: Settings) = JsonStore.write("settings.json", JsonStore.gson.toJson(settings))
}
