package gallery.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

data class Strings(val localeTag: String) {
    fun t(key: StringsKey, vararg args: Any): String {
        val raw = if (localeTag == "en") key.en else key.zh
        return if (args.isNotEmpty()) raw.format(*args) else raw
    }
}

val LocalStrings = staticCompositionLocalOf { Strings("zh") }

object StringsDefaults {
    val system: Strings
        get() = Strings(if (Locale.getDefault().language == "en") "en" else "zh")
}

enum class StringsKey(val zh: String, val en: String) {
    // Toolbar
    OpenFolder("打开文件夹", "Open Folder"),
    OpenArchive("打开压缩包", "Open Archive"),
    Refresh("刷新", "Refresh"),
    Bookshelf("书架", "Bookshelf"),
    Slideshow("幻灯片", "Slideshow"),
    Search("搜索", "Search"),
    SearchHint("搜索图片…", "Search images…"),
    SearchA11y("搜索图片", "Search images"),
    ClearSearch("清除搜索", "Clear search"),
    More("更多", "More"),

    // Menu
    RecursiveOn("含子文件夹 ✓", "Include Subfolders ✓"),
    RecursiveOff("含子文件夹", "Include Subfolders"),
    AddToBookshelf("加入书架", "Add to Bookshelf"),
    Sort("排序", "Sort"),
    Theme("主题", "Theme"),
    Help("帮助", "Help"),
    Language("语言", "Language"),
    LocaleZH("中文", "Chinese"),
    LocaleEN("English", "English"),

    // Theme
    ThemeSystem("跟随系统", "System"),
    ThemeLight("浅色", "Light"),
    ThemeDark("深色", "Dark"),

    // Sort
    SortAsc("升序", "Ascending"),
    SortDesc("降序", "Descending"),
    SortName("按名称排序", "Sort by name"),
    SortSize("按大小排序", "Sort by size"),
    SortDate("按修改时间排序", "Sort by date"),

    // Status
    StatusTotal("%d 张", "%d images"),
    StatusSearching("%d / %d 张", "%d / %d images"),
    StatusWithFolder("%s · %d 张", "%s · %d images"),

    // Empty state
    Empty_NoFolder("尚未打开文件夹", "No folder opened"),
    Empty_NoImages("「%s」中没有找到图片", "No images found in \"%s\""),
    Empty_NoMatch("没有匹配「%s」的图片", "No images matching \"%s\""),
    SelectFolder("选择图片文件夹", "Select image folder"),
    Recent("最近打开", "Recently Opened"),

    // Bookshelf
    BookshelfTitle("书架", "Bookshelf"),
    Back("返回", "Back"),
    BookshelfTotal("共 %d 本", "%d books"),
    BookshelfEmpty("书架是空的\n在图库中打开文件夹或压缩包后，点击「加入书架」即可收藏", "Bookshelf is empty\nOpen a folder or archive, then tap \"Add to Bookshelf\""),
    RemoveBook("移出书架", "Remove from Bookshelf"),

    // Viewer
    Loading("加载中…", "Loading…"),
    LoadAnimationFailed("无法解码此动画", "Unable to decode this animation"),
    Prev("上一张", "Previous"),
    Next("下一张", "Next"),
    Fit("适应", "Fit"),
    Actual("1:1", "1:1"),
    OpenInFolder("在文件夹中显示", "Show in folder"),
    Interval("%ds", "%ds"),
    Delete("删除", "Delete"),
    Info("信息", "Info"),
    Close("关闭", "Close"),
    SlideShow("幻灯片", "Slideshow"),
    FileInfo("文件信息", "File Information"),
    InfoName("名称", "Name"),
    InfoDimensions("尺寸", "Dimensions"),
    InfoFileSize("大小", "File size"),
    InfoPath("路径", "Path"),
    InfoModified("修改时间", "Modified"),

    // Help
    ShortcutsTitle("快捷键", "Keyboard Shortcuts"),
    HelpOpenFolder("Ctrl + O", "Ctrl + O"),
    HelpOpenFolderDesc("打开文件夹", "Open folder"),
    HelpRefresh("F5", "F5"),
    HelpRefreshDesc("刷新当前图集", "Refresh current album"),
    HelpFullscreen("F11", "F11"),
    HelpFullscreenDesc("切换全屏", "Toggle fullscreen"),
    HelpHelp("F1", "F1"),
    HelpHelpDesc("显示本帮助", "Show this help"),
    HelpPrevNext("← / →", "← / →"),
    HelpPrevNextDesc("上一张 / 下一张", "Previous / Next"),
    HelpClose("Esc", "Esc"),
    HelpCloseDesc("关闭大图", "Close viewer"),
    HelpDelete("Delete", "Delete"),
    HelpDeleteDesc("删除当前图片（优先移入回收站）", "Delete current image (move to trash if possible)"),
    HelpInfo("I", "I"),
    HelpInfoDesc("显示 / 隐藏图片信息", "Toggle image info"),
    HelpSlideshow("空格", "Space"),
    HelpSlideshowDesc("开始 / 停止幻灯片", "Start / Stop slideshow"),

    // Dialogs
    ConfirmDeleteTitle("永久删除", "Permanently Delete"),
    ConfirmDeleteText("无法移入回收站，是否永久删除「%s」？此操作不可撤销。", "Cannot move to trash. Permanently delete \"%s\"? This cannot be undone."),
    DeleteConfirm("删除", "Delete"),
    Cancel("取消", "Cancel"),
    AlertTitle("提示", "Notice"),
    OK("确定", "OK"),

    // Errors
    OpenFolderFailed("打开文件夹失败：%s", "Failed to open folder: %s"),
    OpenArchiveFailed("无法打开压缩包：%s", "Failed to open archive: %s"),
    ArchiveEmpty("压缩包内没有找到图片", "No images found in archive"),
    FolderNotFound("文件夹不存在：%s", "Folder not found: %s"),
    RefreshFailed("刷新失败：%s", "Failed to refresh: %s"),

    // Window
    AppTitle("Compose Gallery", "Compose Gallery"),

    // A11y
    A11ySlideshow("幻灯片", "Slideshow"),
    A11yInfo("信息", "Information"),
    A11yClose("关闭", "Close"),
    A11yPrev("上一张", "Previous"),
    A11yNext("下一张", "Next"),
    A11yFit("适应", "Fit to screen"),
    A11yActual("1:1", "Actual size"),
    A11yZoomIn("放大", "Zoom in"),
    A11yZoomOut("缩小", "Zoom out"),
    A11yOpenInFolder("在文件夹中显示", "Show in folder"),
    A11yDelete("删除", "Delete"),
}
