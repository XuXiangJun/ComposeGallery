# Compose Gallery

基于 [Compose Multiplatform / Compose Desktop](https://www.jetbrains.com/compose-multiplatform/) 的桌面图库 / 漫画阅读软件，支持书架收藏与阅读进度。

## 功能

- 打开文件夹，浏览 JPG / PNG / GIF / BMP / WebP / ICO / JFIF / AVIF 图片
- 打开压缩包（**zip / 7z**），直接浏览压缩包内的图片（无需解压）
- 缩略图网格（自适应列数、可调大小、悬停高亮）
- 可选递归扫描子文件夹
- 排序：名称 / 修改时间 / 大小
- 大图查看：鼠标滚轮缩放、拖拽平移、触控板捏合缩放、双击还原；支持动画 GIF 逐帧循环播放
- 键盘：`←`/`→` 上一张/下一张、`Esc` 关闭、`F11` 全屏、`Delete` 删除（优先移入回收站）、`I` 信息、`空格` 幻灯片
- **书架**：收藏文件夹/压缩包为「书」，显示封面、页码与进度，点击从上次进度继续阅读
- **自动保存进度**：翻页/关闭时自动记录，进度持久化到 `~/.ComposeGallery/bookshelf.json`
- 幻灯片播放、在文件管理器中定位、查看图片信息
- 本地缩略图内存缓存（Skia 解码 + 双线性缩放）

> 说明：zip 和 7z 都用 Apache Commons Compress 读取（它比 JDK 自带的 `ZipFile` 宽容，能打开 GBK/Shift-JIS 等中文/日文条目名的压缩包，避免 JDK 的 "invalid CEN header" 报错）；rar 暂不支持（纯 Java 下 RAR5 解压支持不佳）。压缩包内的图片只读，不提供删除。

## 技术栈

- Kotlin 2.1.21 · Compose Multiplatform 1.8.2 · Gradle 8.14.2 · JDK 21（Temurin 21.0.12）
- 图片解码/缩放使用 Skia（skiko），解码时自动应用 EXIF 方向
- 压缩包读取：Apache Commons Compress 1.28.0（zip + 7z，zip 条目名自动检测 UTF-8 / GB18030 编码）

## 运行

本机已准备独立的 JDK 21 与 Gradle（位于 `D:\ai\.tools`），并写好了 `build.ps1`
（它会固定 JDK、把 Gradle 缓存/临时目录/skiko 解压目录都重定向到工作区，规避沙箱限制）。

在 PowerShell 中：

```powershell
# 启动应用
& D:\ai\gallery\build.ps1 run

# 运行测试（JUnit 5 / kotlin.test，覆盖解码/缩放/内存/书架/动画 GIF）
& D:\ai\gallery\build.ps1 test

# 打包 Windows 安装程序（MSI）
& D:\ai\gallery\build.ps1 packageMsi
# 产物：build\compose\binaries\main\msi\ComposeGallery-1.0.0.msi

# 打包免安装 zip（jpackage app-image + zip）
& D:\ai\gallery\build.ps1 packageZip
# 产物：build\compose\binaries\main\app\ComposeGallery-1.0.0-portable.zip
# 解压后运行其中的 ComposeGallery.exe 即可，无需安装
```

### 打包说明

`packageMsi` 用 JDK 自带的 jpackage + jlink + WiX 生成 MSI。首次运行会自动联网下载
WiX 3（约 34 MB，缓存在 `D:\ai\.tools\gradle-home\compose-jb\wix311.zip`）。

> 沙箱内打包的一个特殊处理：WiX 的 `light.exe` 做 ICE 校验时需要访问 Windows
> Installer Service，而沙箱里该服务不可用，会导致打包失败（exit 216）。为此
> `build.gradle.kts` 里的 `wrapWixLight` 任务会把原版 WiX 解压到
> `build/wix311/real/`，再用 `packaging/LightWrapper.cs` 编译出一个 light.exe
> 包装器，链接时自动追加 `-sval` 跳过 ICE 校验。在普通（非沙箱）机器上打包时
> 也可删除这段逻辑。

等价的手工命令（`build.ps1` 内部做的事）：

```powershell
$env:JAVA_HOME = "D:\ai\.tools\jdk21\jdk-21.0.12+8"
$env:GRADLE_USER_HOME = "D:\ai\.tools\gradle-home"
$env:TMP = $env:TEMP = "D:\ai\.tools\tmp"
D:\ai\.tools\gradle-8.14.2\bin\gradle.bat -p D:\ai\gallery run
```

## 操作说明

| 位置 | 操作 |
| --- | --- |
| 网格 | 点击缩略图打开大图；工具栏可「打开文件夹」「打开压缩包」「书架」「加入书架」、换排序、调缩略图大小、切换子文件夹扫描 |
| 书架 | 点击「书架」进入；显示收藏的图集（封面 + 页码 + 进度），点击从上次进度继续，右上角 × 移出书架 |
| 查看器 | 滚轮缩放、拖拽平移、双击还原；底部按钮支持适应 / 1:1 / 缩放 |
| 快捷键 | `←`/`→` 上一张/下一张，`Esc` 关闭，`F11` 全屏，`Delete` 删除，`I` 信息，`空格` 幻灯片 |

## 项目结构

```
gallery/
├── build.gradle.kts          # 构建脚本（含 JUnit5 测试 + 沙箱临时目录重定向 + 打包）
├── build.ps1                 # 一键构建/运行辅助脚本
├── settings.gradle.kts
├── gradle.properties
└── src/main/kotlin/
    ├── Main.kt               # 入口 + 全屏处理
    ├── AppState.kt           # 应用状态 + 书架/进度逻辑
    ├── Model.kt              # ImageSource / ImageItem / 排序
    ├── Bookshelf.kt          # BookEntry + JSON 持久化（Gson）
    ├── ArchiveReader.kt      # 压缩包读取（zip / 7z）
    ├── ImageScanner.kt       # 目录/归档扫描
    ├── ImageLoader.kt        # Skia 解码 + 缩放 + 缩略图缓存
    ├── FileOps.kt            # 打开文件夹/压缩包/回收站/文件管理器
    ├── resources/icon.ico    # 应用图标
    ├── packaging/
    │   └── LightWrapper.cs   # WiX light.exe 包装器（跳过 ICE 校验，沙箱打包用）
    └── ui/
        ├── App.kt            # 顶层编排 + 快捷键 + 幻灯片
        ├── GalleryScreen.kt  # 网格视图
        ├── BookshelfScreen.kt# 书架视图
        ├── ImageViewer.kt    # 大图查看（缩放/平移）
        ├── Thumbnail.kt      # 缩略图组件
        └── Theme.kt          # 暗色主题
└── src/test/kotlin/gallery/
    └── GalleryCoreTest.kt     # JUnit5/kotlin.test 测试（解码/缩放/内存/书架/动画 GIF）
```
