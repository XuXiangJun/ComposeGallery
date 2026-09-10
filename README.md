# Compose Gallery

基于 [Compose Multiplatform / Compose Desktop](https://www.jetbrains.com/compose-multiplatform/) 的桌面图库 / 漫画阅读软件，支持书架收藏与阅读进度。

## 功能

- 打开文件夹，浏览 JPG / PNG / GIF / BMP / WebP / ICO / JFIF / AVIF 图片
- 打开压缩包（**zip / 7z**），直接浏览压缩包内的图片（无需解压）
- 缩略图网格（自适应列数、可调大小、悬停高亮、加载骨架占位）
- 可选递归扫描子文件夹
- 排序：名称 / 修改时间 / 大小，支持升序 / 降序
- 文件名搜索过滤（工具栏搜索框，实时筛选）
- 大图查看：鼠标滚轮缩放、拖拽平移、触控板捏合缩放、双击还原；支持动画 GIF 逐帧循环播放
- 键盘：`←`/`→` 上一张/下一张、`Esc` 关闭、`F11` 全屏、`Delete` 删除（优先移入回收站）、`I` 信息、`空格` 幻灯片、`F1` 快捷键帮助
- **书架**：收藏文件夹/压缩包为「书」，显示封面、页码与进度，点击从上次进度继续阅读
- **自动保存进度**：翻页/关闭时自动记录，进度持久化到 `~/.ComposeGallery/bookshelf.json`
- **主题**：浅色 / 深色 / 跟随系统（工具栏切换），并提供「最近打开」快捷入口
- **幻灯片**：播放间隔可调（大图底部栏滑块）
- 幻灯片播放、在文件管理器中定位、查看图片信息
- 本地缩略图内存缓存（Skia 解码 + 双线性缩放）

> 说明：zip 和 7z 都用 Apache Commons Compress 读取（它比 JDK 自带的 `ZipFile` 宽容，能打开 GBK/Shift-JIS 等中文/日文条目名的压缩包，避免 JDK 的 "invalid CEN header" 报错）；rar 暂不支持（纯 Java 下 RAR5 解压支持不佳）。压缩包内的图片只读，不提供删除。

## 技术栈

- Kotlin 2.4.20 · Compose Multiplatform 1.12.0 · Gradle 9.7.1 · JDK 21（Temurin 21.0.12）
- 图片解码/缩放使用 Skia（skiko），解码时自动应用 EXIF 方向；`compose.desktop.currentOs` 会按当前平台自动解析原生依赖，因此 Windows / Linux / macOS 共用同一份源码
- 压缩包读取：Apache Commons Compress 1.28.0（zip + 7z，zip 条目名自动检测 UTF-8 / GB18030 编码）

## 运行

构建统一走项目自带的 Gradle Wrapper，不再有平台专属辅助脚本。

### 准备

只需要 **JDK 21**（项目用 `jvmToolchain(21)`；Gradle 会自动探测已安装的 JDK，也可用 `JAVA_HOME` 指定）。
**无需手动装 Gradle**——wrapper 会按 `gradle/wrapper/gradle-wrapper.properties` 自动下载 Gradle 9.7.1，
依赖从 Maven Central 自动下载。

### 常用命令

Windows / Linux / macOS 用的是同一套 wrapper，命令一致，只是 Windows 上要写 `gradlew.bat`：

```bash
# macOS / Linux
./gradlew run          # 启动
./gradlew test         # 跑测试
./gradlew packageZip   # 打免安装 zip
```

```powershell
# Windows PowerShell
.\gradlew.bat run
.\gradlew.bat test
.\gradlew.bat packageZip
```

### 分平台打包

分发包格式由 `build.gradle.kts` 里的 `targetFormats` 按当前 OS 自动选择：

| 平台 | 命令 | 产物 | 备注 |
| --- | --- | --- | --- |
| Windows | `.\gradlew.bat packageMsi` | `build/compose/binaries/main/msi/ComposeGallery-1.0.0.msi` | 首次运行自动联网下载 WiX 3 |
| Linux | `./gradlew packageDeb` | `build/compose/binaries/main/deb/*.deb` | 需要系统里有 `dpkg-deb`（多数发行版自带） |
| macOS | `./gradlew packageDmg` | `build/compose/binaries/main/dmg/*.dmg` | 用系统自带 `hdiutil` |
| 任意平台 | `./gradlew packageZip` | `build/compose/binaries/main/app/ComposeGallery-1.0.0.zip` | 免安装，jpackage `app-image` 后打包 |

> macOS 的 `.dmg` 与 Linux 的 `.deb` 图标尚未提供（仓库里只有 Windows 的 `icon.ico`）。
> 备好 `src/main/resources/icon.icns`（macOS）或 `src/main/resources/icon.png`（Linux，建议 512×512）后，
> 打开 `build.gradle.kts` 中 `nativeDistributions` 里的对应 TODO 注释即可启用。
> `packageZip` 在 macOS 上会跳过 `--icon`（jpackage 的 app-image 在 macOS 只接受 `.icns`），
> 且 macOS 的 `.app` 内含符号链接，若在 macOS 上打 zip 分发需另行验证一遍。

> 说明：`packageMsi` 在普通 Windows 上能正常做 ICE 校验；`wrapWixLight` 的 light.exe
> 包装器只在沙箱环境（WiX 缓存存在）时启用，普通机器会自动跳过。

### 受限 / CI 环境

Gradle 的用户目录默认是 `~/.gradle`（Windows 为 `%USERPROFILE%\.gradle`）：依赖缓存、wrapper
下载的 Gradle 分发包都放在那里。若该目录（或系统临时目录）不可写，用环境变量指到可写位置即可：

```bash
GRADLE_USER_HOME=/tmp/gradle-home TMPDIR=/tmp ./gradlew test
```

```powershell
$env:GRADLE_USER_HOME = "D:\gradle-home"; $env:TMP = $env:TEMP = "D:\tmp"; .\gradlew.bat test
```

### 打包说明

`packageMsi` 用 JDK 自带的 jpackage + jlink + WiX 生成 MSI。首次运行会自动联网下载
WiX 3（约 34 MB，缓存到 Gradle 用户目录下的 `compose-jb/wix311.zip`）。

> 沙箱内打包的一个特殊处理：WiX 的 `light.exe` 做 ICE 校验时需要访问 Windows
> Installer Service，而沙箱里该服务不可用，会导致打包失败（exit 216）。为此
> `build.gradle.kts` 里的 `wrapWixLight` 任务会把原版 WiX 解压到
> `build/wix311/real/`，再用 `packaging/LightWrapper.cs` 编译出一个 light.exe
> 包装器，链接时自动追加 `-sval` 跳过 ICE 校验。在普通（非沙箱）机器上打包时
> 也可删除这段逻辑。

## 操作说明

| 位置 | 操作 |
| --- | --- |
| 网格 | 点击缩略图打开大图；工具栏可「打开文件夹」「打开压缩包」「书架」「加入书架」、换排序与升降序、调缩略图大小、切换子文件夹扫描、搜索图片 |
| 网格 | 未打开任何图集时显示「最近打开」快捷入口；工具栏可切换浅色 / 深色 / 跟随系统主题 |
| 书架 | 点击「书架」进入；显示收藏的图集（封面 + 页码 + 进度），点击从上次进度继续，右上角 × 移出书架 |
| 查看器 | 滚轮缩放、拖拽平移、双击还原；底部按钮支持适应 / 1:1 / 缩放，并可调幻灯片播放间隔 |
| 快捷键 | `←`/`→` 上一张/下一张，`Esc` 关闭，`F11` 全屏，`Delete` 删除，`I` 信息，`空格` 幻灯片，`F1` 快捷键帮助，`Ctrl+O` 打开文件夹，`F5` 刷新 |

## 项目结构

```
gallery/
├── build.gradle.kts          # 构建脚本（平台判定 + JUnit5 测试 + 沙箱临时目录重定向 + 打包）
├── .gitattributes            # 行尾规则（gradlew/*.sh 固定 LF，*.bat/*.ps1 固定 CRLF）
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat     # Gradle Wrapper（Linux/macOS 用 gradlew，Windows 用 gradlew.bat）
└── src/main/kotlin/
    ├── Main.kt               # 入口 + 全屏处理
    ├── AppState.kt           # 应用状态 + 书架/进度/最近打开/主题/搜索
    ├── Model.kt              # ImageSource / ImageItem / 排序（含升降序）
    ├── Bookshelf.kt          # BookEntry + JSON 持久化（Gson）
    ├── Settings.kt           # 主题模式/排序方向/幻灯片间隔/最近打开持久化（Gson）
    ├── ArchiveReader.kt      # 压缩包读取（zip / 7z）
    ├── ImageScanner.kt       # 目录/归档扫描
    ├── ImageLoader.kt        # Skia 解码 + 缩放 + 缩略图缓存
    ├── FileOps.kt            # 打开文件夹/压缩包/回收站/文件管理器
    ├── resources/icon.ico    # 应用图标（Windows；macOS/Linux 图标待补，见「运行」一节）
    ├── packaging/
    │   └── LightWrapper.cs   # WiX light.exe 包装器（跳过 ICE 校验，沙箱打包用）
    └── ui/
        ├── App.kt            # 顶层编排 + 快捷键 + 幻灯片
        ├── GalleryScreen.kt  # 网格视图
        ├── BookshelfScreen.kt# 书架视图
        ├── ImageViewer.kt    # 大图查看（缩放/平移）
        ├── Thumbnail.kt      # 缩略图组件
        └── Theme.kt          # 主题（光/暗/跟随系统）+ 语义色/间距/圆角 token
└── src/test/kotlin/gallery/
    └── GalleryCoreTest.kt     # JUnit5/kotlin.test 测试（解码/缩放/内存/书架/动画 GIF）
```
