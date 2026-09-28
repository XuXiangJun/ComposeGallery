package gallery.i18n

import androidx.compose.runtime.staticCompositionLocalOf

data class Strings(val localeTag: String) {
    fun t(key: StringsKey, vararg args: Any): String {
        val raw = if (localeTag == "en") key.en else key.zh
        return if (args.isNotEmpty()) raw.format(*args) else raw
    }
}

val LocalStrings = staticCompositionLocalOf { Strings("zh") }

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
    ThumbSize("缩略图大小", "Thumbnail size"),

    // Menu
    RecursiveOn("含子文件夹 ✓", "Include Subfolders ✓"),
    RecursiveOff("含子文件夹", "Include Subfolders"),
    AddToBookshelf("加入书架", "Add to Bookshelf"),
    Sort("排序", "Sort"),
    Theme("主题", "Theme"),
    Help("帮助", "Help"),
    Language("语言", "Language"),
    LocaleSystem("跟随系统", "System"),
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
    SelectArchive("选择压缩包", "Select archive"),
    ArchiveFilter("压缩包 (*.zip, *.cbz, *.7z, *.cb7)", "Archives (*.zip, *.cbz, *.7z, *.cb7)"),
    Recent("最近打开", "Recently Opened"),
    RecentFolder("文件夹", "Folder"),
    RecentArchive("压缩包", "Archive"),

    // Bookshelf
    BookshelfTitle("书架", "Bookshelf"),
    Back("返回", "Back"),
    BookshelfTotal("共 %d 本", "%d books"),
    BookshelfEmpty("书架是空的\n在图库中打开文件夹或压缩包后，点击「加入书架」即可收藏", "Bookshelf is empty\nOpen a folder or archive, then tap \"Add to Bookshelf\""),
    RemoveBook("移出书架", "Remove from Bookshelf"),
    BookshelfSortRecent("按最近阅读", "Recently read"),
    BookshelfSortName("按名称", "By name"),
    BookMissing("路径已失效", "Missing"),
    BookFinished("已读完", "Finished"),
    RemoveRecent("从最近打开中移除", "Remove from recent"),
    ClearRecent("清空", "Clear"),

    ThumbnailFailed("无法加载缩略图", "Thumbnail unavailable"),

    // Viewer
    Loading("加载中…", "Loading…"),
    LoadFailed("无法解码这张图片", "Unable to decode this image"),
    LoadAnimationFailed("无法解码此动画", "Unable to decode this animation"),
    Prev("上一张", "Previous"),
    Next("下一张", "Next"),
    Fit("适应", "Fit"),
    Actual("1:1", "1:1"),
    OpenInFolder("在文件夹中显示", "Show in folder"),
    OpenExternal("用默认程序打开", "Open with default app"),
    Rotate("旋转", "Rotate"),
    Copied("已复制到剪贴板", "Copied to clipboard"),
    CopyFailed("复制失败", "Copy failed"),
    DirectionLtr("左→右", "L→R"),
    DirectionRtl("右→左", "R→L"),
    WheelZoom("滚轮：缩放", "Wheel: zoom"),
    WheelPage("滚轮：翻页", "Wheel: page"),
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
    HelpPage("PgUp / PgDn", "PgUp / PgDn"),
    HelpPageDesc("上一张 / 下一张", "Previous / Next"),
    HelpHomeEnd("Home / End", "Home / End"),
    HelpHomeEndDesc("第一张 / 最后一张", "First / Last image"),
    HelpZoom("+ / - / 0 / 1", "+ / - / 0 / 1"),
    HelpZoomDesc("放大 / 缩小 / 适应 / 1:1", "Zoom in / out / Fit / 1:1"),
    HelpThumbZoom("Ctrl + 滚轮", "Ctrl + Wheel"),
    HelpThumbZoomDesc("网格中调整缩略图大小", "Resize thumbnails in grid"),
    HelpClose("Esc", "Esc"),
    HelpCloseDesc("关闭大图", "Close viewer"),
    HelpDelete("Delete", "Delete"),
    HelpDeleteDesc("删除当前图片（优先移入回收站）", "Delete current image (move to trash if possible)"),
    HelpRotate("R / Shift + R", "R / Shift + R"),
    HelpRotateDesc("顺时针 / 逆时针旋转", "Rotate clockwise / counter-clockwise"),
    HelpFlip("H", "H"),
    HelpFlipDesc("水平翻转", "Flip horizontally"),
    HelpCopy("Ctrl + C", "Ctrl + C"),
    HelpCopyDesc("复制图片到剪贴板", "Copy image to clipboard"),
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
    UnsupportedPath("无法打开：%s（只支持文件夹、zip / cbz / 7z / cb7 压缩包和图片）", "Cannot open %s (folders, zip / cbz / 7z / cb7 archives and images only)"),
    EncryptedZipUnsupported("「%s」是加密的 zip，暂不支持（加密的 7z 可以输入密码打开）", "\"%s\" is an encrypted zip, which is not supported (encrypted 7z archives can be opened with a password)"),
    PasswordTitle("需要密码", "Password Required"),
    PasswordPrompt("「%s」已加密，请输入密码：", "\"%s\" is encrypted. Enter the password:"),
    PasswordWrong("密码错误或压缩包已损坏，请重新输入「%s」的密码：", "Wrong password or damaged archive. Enter the password for \"%s\":"),
    ScanTruncated("图片太多，只显示了前 %d 张", "Too many images; only the first %d are shown"),
    DeleteFailed("无法删除「%s」（文件可能被占用或没有权限）", "Could not delete \"%s\" (file may be in use or access denied)"),

    // Window
    AppTitle("Compose Gallery", "Compose Gallery"),

    // A11y
    A11yZoomIn("放大", "Zoom in"),
    A11yZoomOut("缩小", "Zoom out"),
}
