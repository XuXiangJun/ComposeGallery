package gallery

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Locale

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

enum class SortDirection(val label: String) {
    ASC("升序"),
    DESC("降序"),
}

enum class AppLocale(val tag: String, val zhLabel: String, val enLabel: String) {
    SYSTEM("system", "跟随系统", "System"),
    ZH("zh", "中文", "Chinese"),
    EN("en", "English", "English");

    fun resolved(defaultTag: String): String = if (this == SYSTEM) defaultTag else tag
}

/** 「最近打开」的一条记录，type 与 [BookEntry.type] 一致："folder" | "archive"。 */
data class RecentEntry(
    val path: String,
    val name: String,
    val type: String,
    val lastRead: Long,
)

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val sortDirection: SortDirection = SortDirection.ASC,
    val slideshowSeconds: Float = 3f,
    val recent: List<RecentEntry> = emptyList(),
    val locale: AppLocale = AppLocale.SYSTEM,
) {
    fun localeTag(): String = locale.resolved(defaultLocaleTag())

    companion object {
        fun defaultLocaleTag(): String = if (Locale.getDefault().language == "en") "en" else "zh"
    }
}

object SettingsStore {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val type = object : TypeToken<Settings>() {}.type

    val dir: File = File(System.getProperty("user.home"), ".ComposeGallery")
    val file: File = File(dir, "settings.json")

    fun load(): Settings = try {
        if (!file.exists()) Settings()
        else gson.fromJson(file.readText(), type) ?: Settings()
    } catch (_: Throwable) {
        Settings()
    }

    fun save(settings: Settings) {
        try {
            dir.mkdirs()
            file.writeText(gson.toJson(settings))
        } catch (_: Throwable) {
            // 沙箱或权限问题下静默失败（设置仅本次会话有效）
        }
    }
}
