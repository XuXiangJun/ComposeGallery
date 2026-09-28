# Compose Gallery

基于 [Compose Multiplatform / Compose Desktop](https://www.jetbrains.com/compose-multiplatform/) 的桌面图库 / 漫画阅读软件，支持书架收藏与阅读进度。

## 功能

- 打开文件夹，浏览 JPG / PNG / GIF / BMP / WebP / ICO / JFIF 图片（skiko 没有 AVIF / HEIF 解码器，不列入）
- 打开压缩包（**zip / cbz / 7z / cb7**），直接浏览压缩包内的图片（无需解压）；加密的 7z 会弹框要密码，
  加密的 zip 暂不支持（会明确提示）；自动跳过 macOS 的 `__MACOSX/`、`._xxx` 元数据
- 拖文件夹 / 压缩包 / 图片到窗口即可打开；也可作为命令行参数（「打开方式」/ 文件关联）传入
- 文件名**自然排序**（`2.jpg` 排在 `10.jpg` 前面）
- 缩略图网格（自适应列数、可调大小、悬停高亮、加载骨架占位）
- 可选递归扫描子文件夹
- 排序：名称 / 修改时间 / 大小，支持升序 / 降序
- 文件名搜索过滤（工具栏搜索框，实时筛选）
- 大图查看：鼠标滚轮缩放（或切换为滚轮翻页，Ctrl+滚轮始终缩放）、拖拽平移、触控板捏合缩放、双击还原、
  点击左 / 右三分之一翻页；旋转 / 翻转、复制到剪贴板、用默认程序打开、在文件管理器中选中；
  支持动画 GIF（按 disposal 规则逐帧合成，差分帧不花屏）
- **从右往左阅读**（日漫）：←、底栏箭头、点击区域的方向随之对调
- 全屏时鼠标静止 2.5 秒自动隐藏工具栏和指针
- 键盘：见下方「操作说明」，`F1` 查看全部快捷键
- **书架**：收藏文件夹/压缩包为「书」，显示封面、页码与进度（已读完 / 路径失效会标出来），支持搜索与按名称 / 最近阅读排序，
  点击从上次进度继续阅读；进度按「图片路径 + 下标」记录，换排序或增删文件后仍回到同一张图
- **自动保存进度**：翻页/关闭窗口时自动记录，进度持久化到 `~/.ComposeGallery/bookshelf.json`；
  JSON 损坏时先备份为 `*.corrupt-<时间戳>` 再退回默认值
- 记住排序方式、子文件夹开关、缩略图大小、窗口位置与大小、阅读方向、滚轮模式
- **主题**：浅色 / 深色 / 跟随系统（工具栏切换），并提供「最近打开」快捷入口
- **中英文界面**：工具栏「语言」菜单切换，选择结果持久化
- **幻灯片**：播放间隔可调（大图底部栏滑块）
- 幻灯片播放、在文件管理器中定位、查看图片信息
- 缩略图内存缓存 + 磁盘缓存（`~/.ComposeGallery/thumbs/`，WebP，上限 256MB）；大图缓存按字节限额
- 日志：`~/.ComposeGallery/logs/gallery.log`（解码失败、存盘失败等静默降级的情况都会记下来）

> 说明：zip 和 7z 都用 Apache Commons Compress 读取（它比 JDK 自带的 `ZipFile` 宽容，能打开 GBK/Shift-JIS 等中文/日文条目名的压缩包，避免 JDK 的 "invalid CEN header" 报错）；rar 暂不支持（纯 Java 下 RAR5 解压支持不佳）。压缩包内的图片只读，不提供删除。
> zip 条目名的编码是**探测**出来的：先按 UTF-8 打开，再把条目名的原始字节（`rawName`）做一次严格 UTF-8 解码，失败时再判断是不是 Shift-JIS（能严格解码且含全角假名），否则改用 GB18030 重开。之所以不看解码后的字符，是因为 commons-compress 把畸形 UTF-8 字节解成 `?`（合法文件名字符，会误判），而且畸形字节还可能恰好拼成合法 UTF-8。

## 技术栈

- Kotlin 2.4.20 · Compose Multiplatform 1.12.1 · Gradle 9.7.1 · JDK 21（Temurin 21.0.12）
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
> `packageZip` 每次打包前会先清理上次的 app image 目录（jpackage 生成的 launcher 带只读属性，
> 因此清理时会先去只读位）；若该目录被其它进程占用，报错会给出路径，手动删除后重试即可。

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

`packageZip` 分两步：自定义任务 `createPortableAppImage`（jpackage `--type app-image`），再由 Gradle
自带的 `Zip` 任务打包（`useFileSystemPermissions()`，Linux 上 launcher 的可执行位才不会丢）。
几个容易踩的实现细节都已在 `build.gradle.kts` 里注释：

- **runtime 必须是 JDK 21**。Compose 的 jlink 默认用「跑 Gradle 的那个 JDK」；Gradle 跑在 JDK 17 上时，
  打出来的程序一启动就 `UnsupportedClassVersionError`。所以 `compose.desktop.application.javaHome`
  显式指向 toolchain 的 JDK 21。
- **runtime 要带 `jdk.unsupported`**。Gson 反序列化 `BookEntry` / `RecentEntry` 依赖 `sun.misc.Unsafe`，
  缺了它打包版每次启动都读不回书架和最近打开（单元测试跑在完整 JDK 上，发现不了）。

- **skiko 原生库必须显式放进 `app/`**。`.cfg` 里 `app.classpath=$APPDIR\*.jar` 说明 `$APPDIR`
  就是 jar 所在的 `app/` 目录，而 `-Dskiko.library.path=$APPDIR` 会让 skiko 到这里找
  `skiko-windows-x64.dll`。Compose 1.12.x 已不再把原生库解压到 `build/compose/tmp/skiko`，
  所以改为直接从 classpath 上的 `skiko-awt-runtime-*` jar 里提取，取不到就让构建失败
  （否则是「打包成功、一运行就 LibraryLoadException」）。
- **jar 文件名必须全局唯一**。runtimeClasspath 里存在 groupId 不同、基名却相同的 artifact
  （`androidx.compose.runtime:runtime-saveable-desktop:1.12.1` 与
  `org.jetbrains.compose.runtime:runtime-saveable-desktop:1.12.1`，后者是只有 LICENSE 的空壳），
  按原名拷进同一个目录会互相覆盖，导致 `NoClassDefFoundError`。所以输出名带上 group
  （如 `androidx.compose.runtime_runtime-saveable-desktop_1.12.1.jar`），并在拷贝前校验无重名。

`verifyPackageZip` 会检查：skiko 原生库在 app 目录里、`.cfg` 引用的 jar 都存在、runtime 版本 ≥ 21
且含 `jdk.unsupported`、非 Windows 上 launcher 可执行（app 目录位置按平台区分：Windows `app/`、
Linux `lib/app/`、macOS `Contents/app/`）。

### CI

仓库带 `.github/workflows/ci.yml`：push / PR 到 `master` 时，在 `ubuntu-latest` 与
`windows-latest` 上以 Temurin JDK 21 跑 `./gradlew test`，再跑一遍 `./gradlew packageZip`
（jpackage app-image，不需要 WiX），产物上传为 artifact；推送 `v*` tag 时额外把各平台 zip
发布到 GitHub Release。macOS 暂未纳入打包矩阵：
`.app` 内含符号链接、`packageZip` 的 macOS 路径尚未验证过（见上文 TODO）。

### 沙箱 / 受限环境构建

`build.gradle.kts` 只包含可移植逻辑，不指向任何特定机器。沙箱专用的那些辅助
（临时目录重定向、包装 WiX `light.exe` 跳过 ICE 校验）全部收在 `gradle/sandbox.gradle.kts`，
**默认不加载**，需要时显式开启：

```bash
./gradlew -Psandbox packageMsi        # macOS / Linux
.\gradlew.bat -Psandbox packageMsi    # Windows
GRADLE_SANDBOX=1 ./gradlew packageMsi # 与 -Psandbox 等效
```

`-Psandbox` 会做两件事：把 `run` 的 `java.io.tmpdir` / `skiko.data.path` 指到可写位置，
以及让 `packageMsi` 依赖 `wrapWixLight`（ICE 校验需要 Windows Installer Service，沙箱里
该服务不可用会让 `light.exe` 以 exit 216 失败）。普通 Windows 机器上不需要它 —— WiX 3
会照常自动下载、ICE 校验也能通过；详见该文件顶部注释。

## 操作说明

| 位置 | 操作 |
| --- | --- |
| 网格 | 点击缩略图打开大图；工具栏可「打开文件夹」「打开压缩包」「书架」「加入书架」、换排序与升降序、调缩略图大小（滑块或 `Ctrl+滚轮`）、切换子文件夹扫描、搜索图片；关闭大图后回到原来的滚动位置并高亮刚看的那张 |
| 网格 | 未打开任何图集时显示「最近打开」（可单条移除 / 清空）；工具栏可切换浅色 / 深色 / 跟随系统主题与界面语言（含「跟随系统」） |
| 书架 | 点击「书架」进入；显示收藏的图集（封面 + 页码 + 进度），可搜索、切换排序，点击从上次进度继续，右上角 × 移出书架 |
| 查看器 | 滚轮缩放或翻页、拖拽平移、双击还原、点击左右两侧翻页；底部按钮：阅读方向、滚轮模式、适应 / 1:1 / 缩放 / 旋转、定位文件、用默认程序打开、幻灯片间隔、删除 |
| 快捷键 | `←`/`→`、`PgUp`/`PgDn` 上一张/下一张，`Home`/`End` 首张/末张，`+`/`-`/`0`/`1` 放大/缩小/适应/1:1，`R`/`Shift+R` 旋转，`H` 翻转，`Ctrl+C` 复制，`Esc` 关闭，`F11` 全屏，`Delete` 删除，`I` 信息，`空格` 幻灯片，`F1` 快捷键帮助，`Ctrl+O` 打开文件夹，`F5` 刷新 |

## 项目结构

```
gallery/
├── build.gradle.kts               # 构建脚本（平台判定 + JUnit5 测试 + 打包；沙箱逻辑不在这里）
├── settings.gradle.kts
├── gradle.properties
├── .gitattributes                 # 行尾规则（gradlew/*.sh 固定 LF，*.bat/*.ps1 固定 CRLF）
├── gradlew / gradlew.bat          # Gradle Wrapper（Linux/macOS 用 gradlew，Windows 用 gradlew.bat）
├── gradle/
│   ├── wrapper/                   # wrapper jar + gradle-wrapper.properties（决定 Gradle 版本）
│   └── sandbox.gradle.kts         # 沙箱专用构建辅助（-Psandbox 才加载）：临时目录重定向 + WiX light.exe 包装
├── .github/workflows/ci.yml       # CI：ubuntu/windows × JDK21 跑 test + packageZip
├── packaging/
│   └── LightWrapper.cs            # WiX light.exe 包装器（跳过 ICE 校验，沙箱打包用）
└── src/
    ├── main/
    │   ├── resources/icon.ico     # 应用图标（Windows；macOS/Linux 图标待补，见「运行」一节）
    │   └── kotlin/
    │       ├── Main.kt            # 入口（命令行路径）+ 全屏 + 窗口图标 / 位置恢复
    │       ├── AppLog.kt          # 文件日志（~/.ComposeGallery/logs）
    │       ├── AppState.kt        # 应用状态 + 书架/进度/最近打开/主题/搜索
    │       ├── Model.kt           # ImageSource / ImageItem / 排序（含升降序）
    │       ├── Bookshelf.kt       # BookEntry + 书架 JSON 持久化（经 JsonStore 原子写）
    │       ├── Settings.kt        # 主题/排序方向/幻灯片间隔/最近打开/语言持久化（同上）
    │       ├── JsonStore.kt       # 数据目录 + JSON 读写 + 原子写（临时文件 + ATOMIC_MOVE）
    │       ├── ArchiveReader.kt   # 压缩包读取（zip / 7z，条目名编码探测）
    │       ├── ImageScanner.kt    # 目录 / 归档扫描
    │       ├── ImageLoader.kt     # Skia 解码 + 缩放 + 缩略图内存 / 磁盘缓存
    │       ├── FileOps.kt         # 文件对话框、回收站、文件管理器定位、默认程序打开、剪贴板
    │       ├── gallery/
    │       │   └── GifDecoder.kt  # 动画 GIF 解码（帧 + 延时 + 循环次数）
    │       ├── i18n/
    │       │   └── Strings.kt     # 中英文文案（StringsKey 枚举 + LocalStrings）
    │       └── ui/
    │           ├── App.kt             # 顶层编排 + 快捷键 + 幻灯片 + 对话框
    │           ├── GalleryScreen.kt   # 网格视图 + 工具栏（排序/主题/语言菜单）
    │           ├── BookshelfScreen.kt # 书架视图
    │           ├── ImageViewer.kt     # 大图查看（缩放/平移/信息面板/加载失败态）
    │           ├── Thumbnail.kt       # 缩略图组件
    │           └── Theme.kt           # 主题（光/暗/跟随系统）+ 语义色/间距/圆角 token
    └── test/kotlin/gallery/
        └── GalleryCoreTest.kt     # JUnit5/kotlin.test 测试（解码/缩放/内存/书架/进度/编码回退）
```

> 数据目录默认 `~/.ComposeGallery`，可用 `-Dcompose.gallery.home=<dir>` 整体改到别处
> （单元测试靠它把读写隔离到临时目录，不会碰真实的用户数据）。
