# 代码审查报告 — 2026-09-28（deepseek）

> 审查对象：`dev` 分支上的 6 个提交，范围 `b25ae8e..HEAD`（26 个文件，+1987 / −449）。
> 审查方式：逐文件阅读 + 实际执行验证（编译、测试、打包、产物核对、独立 JVM 探针）。
> 本报告只记录**可复核**的结论：实测能复现的标「实测」，只靠阅读代码得出的标「代码核对」。

## 0. 验证方法（本次实际执行过的东西）

| 验证项 | 命令 / 方法 | 结果 |
| --- | --- | --- |
| 单元测试 | `.\gradlew.bat test` | 32 tests / 0 failures（`:test` 真实执行） |
| 打包自检 | `.\gradlew.bat packageZip verifyPackageZip` | 成功；skiko 原生库就位、classpath 条目齐全 |
| 产物核对 | 解 `ComposeGallery-1.0.0.zip` 看 224 个条目 | 布局正确（`ComposeGallery.exe` / `app/` / `runtime/` 平铺于根） |
| 打包 runtime | 读 `app/ComposeGallery/runtime/release` | `JAVA_VERSION="21.0.12"`，`MODULES` 含 **`jdk.unsupported`** |
| Gson null 安全 | 独立 JVM 探针，用项目自带 gson 2.14.0 + 编译产物 | 非法枚举→SYSTEM、字段 null→默认、越界数值→默认，均不抛异常 |
| `naturalCompare` | 移植原逻辑到独立 JVM 程序 | 复现出三点循环（见 2.2） |
| Shift-JIS 编码 | 独立 JVM 探针（项目自带 JDK） | 复现出 CP932 扩展字导致误判（见 2.3） |
| 7z 读取 | 用**打包产物自己的 classpath** 打开真实 73MB `.7z` | `NoClassDefFoundError: org/tukaani/xz/FilterOptions`（见 2.1） |
| 库行为核对 | 解出 `commons-compress-1.28.0-sources.jar` 阅读 `SevenZFile.java` | 证实 `contentMethods` 在 open 阶段恒为 null |

**总结论：今天的四个招牌修复（图集加载串台、进度丢失、GIF 差分帧花屏、打包产物起不来）经代码与实跑验证都站得住；两处 AGENTS.md 硬不变量没有被破坏。** 未发现今天引入的 P0；发现 1 个功能级 P0（根因早于今天，但今天的 cbz/cb7 与加密 7z 全部建立其上）、2 个今天引入的 P1、9 个 P2、若干 P3。

## 1. 结论摘要

| 级别 | 是否今天引入 | 问题 | 位置 |
| --- | --- | --- | --- |
| P0 | 否（今天的功能全部依赖它） | 7z / cb7 完全无法打开：运行时缺 `org.tukaani:xz` | `build.gradle.kts:46`、`ArchiveReader.kt:141-156` |
| P1 | 是 | `naturalCompare` 违反比较器传递性（Unicode Nd 数字） | `Model.kt:109-125` |
| P1 | 是 | Shift-JIS 探测用严格 `Shift_JIS`，一个 CP932 扩展字即整包乱码 | `ArchiveReader.kt:184-198` |
| P2 | 是 | Ctrl+滚轮每次事件都写 `settings.json`（UI 线程） | `GalleryScreen.kt:194-202` |
| P2 | 是 | `moveTick++` 使整个查看器按鼠标移动频率重组 | `ImageViewer.kt:271-279`、`:315` |
| P2 | 是 | 书架封面缓存 key 缺 mtime/size，换过的压缩包一直用旧封面 | `Model.kt:48`、`BookshelfScreen.kt:215` |
| P2 | 是 | 归档条目「用默认程序打开」在 UI 线程解整条条目 | `ImageViewer.kt:447` → `FileOps.kt:97-108` |
| P2 | 是 | `verifyPackageZip` 从不打开它要守的那个 zip | `build.gradle.kts:306-385` |
| P2 | 是 | toolchain 在配置期 `.get()`，任何 gradle 命令都要求本机有 JDK 21 | `build.gradle.kts:71-78` |
| P2 | 是 | CI 只对 `master`/PR 触发，今天的改动推在 `dev` 上，等于没跑 | `.github/workflows/ci.yml:4-6` |
| P2 | 是 | AES 内容加密的「提前识别」是死代码（恒 false） | `ArchiveReader.kt:148-150` |
| P2 | 是 | GIF 合成画布按逻辑画布尺寸分配，不受像素预算约束 | `GifDecoder.kt:80`、`:91` |

P3 见第 4 节；既有（非今天引入）问题见第 5 节；测试缺口见第 6 节；核对通过的部分见第 7 节。

## 2. P0 / P1

### 2.1 7z 与 cb7 实际上完全打不开（功能级 P0）— **实测**

用今天打出的包（54 个 jar）当 classpath，打开机器上一份真实的 73MB `.7z`：

```
archive exists=true size=73117502
STEP open()      : FAILED -> java.lang.NoClassDefFoundError: org/tukaani/xz/FilterOptions
```

证据链：

- 打包产物 `app/` 里只有 `commons-compress_1.28.0` + `commons-io` + `commons-codec`，**没有任何 xz / tukaani jar**；`~/.gradle/caches/.../files-2.1` 下也没有 `org.tukaani`。
- `Class.forName("...sevenz.Coders")` → `NoClassDefFoundError: org/tukaani/xz/FilterOptions`（cause: `ClassNotFoundException`），而 `SevenZFile` 本身能加载。原因是 `Coders` 的静态块无条件引用了 xz 的类（`Coders.java:216 new X86Options()`），而 `SevenZFile.java:787` / `:1254` 每次构建解码流都会走它。commons-compress 把 xz 标为 optional，`build.gradle.kts:46` 只声明了 commons-compress。
- **归属**：`git show b25ae8e:build.gradle.kts` 同样没有 xz → 根因**不是**今天引入的。但今天整条线都压在它上面：`ArchiveReader.kt:130-156` 的 cbz/cb7 支持、今天新加的加密 7z 密码弹框、`openSevenZ` 的 AES 预检测（见 3.5）、以及今天更新的 README（"zip / cbz / 7z / cb7"、"加密的 7z 会弹框要密码"）全部不可达。用户打开 `.7z` 只会看到 `App.kt:190-198` 兜出来的「无法打开压缩包：org/tukaani/xz/FilterOptions」。
- **修法**：`implementation("org.tukaani:xz:1.10")`，并补一个真实的 7z 往返测试（`SevenZOutputFile` 写 → `ArchiveReader` 读）。目前 `src/test` 里 **0 个** 7z/cb7 用例，这正是它一路绿灯的原因。

### 2.2 `naturalCompare` 违反比较器传递性 — **实测**（今天引入）

移植 `Model.kt:103-129` 的原逻辑到独立 JVM 程序：

```
cmp(12,a)  = -48        cmp(a,１２) = -65200      cmp(１２,12) = -1
CYCLE 12 < a < １２ < 12 : true
```

原因：`Char.isDigit()` 是 Unicode Nd（全角 ０-９、阿拉伯-印度数字…），数字段按「去掉 ASCII `0` 后比长度 / 字典序」比较，而字符段按码点比较，两套规则混用后不再构成全序。

**影响**：同一列表里混有全角数字名（`１２.jpg`、`第１話`）与 ASCII 数字/字母名时，排序结果取决于输入顺序。网格与查看器共用同一份列表，所以不会自相矛盾，但顺序不可预测。

**对「会抛异常」说法的保留**：另一路审查称在特定数据上 TimSort 会抛 `IllegalArgumentException: Comparison method violates its general contract!`（466/20000）。我按两套数据（含「多章 cbz 重复显示名」场景）各洗牌 5000 次，**一次都没能复现抛异常**。契约违反理论上确实可能让 TimSort 抛，但本报告只按「顺序不可靠」记录，不按「会崩」记录。

**修法**：数字段改成 ASCII 判定 `ca in '0'..'9' && cb in '0'..'9'`（此后等价于在规范化 key 上的字典序，是全序），并补一个含全角数字与重复名的排序用例。

### 2.3 Shift-JIS 探测用严格 `Shift_JIS`，遇 CP932 扩展字整包乱码 — **实测**（今天引入）

用项目自带的 JDK 实测：

```
U+2460 ① cp932=8740  Shift_JIS.strictDecode=false  sjis.canEncode=false  cp932.canEncode=true
U+3231 ㈱ cp932=878A  Shift_JIS.strictDecode=false  sjis.canEncode=false  cp932.canEncode=true
U+9AD9 髙 cp932=FBFC  Shift_JIS.strictDecode=false  sjis.canEncode=false  cp932.canEncode=true
U+3042 あ cp932=82A0  Shift_JIS.strictDecode=true   sjis.canEncode=true   cp932.canEncode=true
```

即 `ArchiveReader.kt:184-198` 里只要出现一个 `①` / `㈱` / `髙` 这类 CP932 扩展字，严格解码就抛 `MalformedInputException` → `looksLikeShiftJis` 返回 `false` → 整个日文压缩包改按 GB18030 打开 → **所有条目名乱码**。

**修法**：探测与回退都改用 `windows-31j`（它是 Shift_JIS 的超集；`:195` 的「至少一个全角假名」判据仍能把 GBK 区分开），并补一个含 CP932 专有字符的用例。

## 3. P2（均为今天引入）

1. **Ctrl+滚轮调缩略图：每个滚轮事件都写一次 `settings.json`（UI 线程）**
   `GalleryScreen.kt:194-202` 在 `onPointerEvent(Scroll)` 里同时调 `onThumbSizeChange` 与 `onThumbSizeChangeFinished`，后者接到 `App.kt:502` → `persistThumbSize()` → `SettingsStore.save`（临时文件 + `ATOMIC_MOVE`）。触控板一次滑动会连发几十个事件（同一份代码在 `ImageViewer.kt:653` 的注释里自己写了这点），而紧邻的滑块（`GalleryScreen.kt:280`）明确写着「松手才回调持久化」。建议改成 `LaunchedEffect(thumbSize) { delay(400); persist() }`。

2. **`moveTick++` 让整个查看器按鼠标移动频率重组**
   `ImageViewer.kt:271-279` 把 `moveTick` 当作 `LaunchedEffect` 的 key 在本组件作用域读取，`:315` 每个 Move 事件自增；子组件拿到的是每次重组新建的 lambda（永不相等），`ZoomableImage` 连同 `FilterQuality.Medium` 的 `drawImage` 会跟着重跑——**非全屏时也一样**（此时 effect 体只是把 `pointerIdle` 置回 `false`）。建议只在 `fullscreen` 时计数，或改用空闲时间戳。

3. **书架封面缓存 key 缺 mtime/size → 换过的压缩包一直用旧封面**
   `Model.kt:48` `"cover:${path}!/$entryName"`，而 `FileSource`/`ArchiveSource` 都带 `@$modified:$size`（正是磁盘缩略图缓存文档承诺的「原图改动后自然失效」）。同名同路径重下、首图条目名不变时，旧 `.webp` 会**跨重启**一直生效（内存 + `<数据目录>/thumbs` 的 256MB 磁盘缓存，`ImageLoader.kt:181`）。修法：把归档文件的 `length()`/`lastModified()` 拼进 key。

4. **归档条目「用默认程序打开」在 UI 线程解整条条目**
   `ImageViewer.kt:447` → `FileOps.kt:97-108`：`item.source.openBytes()` 即 `ArchiveReader.readEntry`（`@Synchronized`、上限 512MB）再接 `writeBytes`，全在 EDT 上；`openInFileManager` 在非 Windows 上还有 `proc.waitFor()`（`FileOps.kt:68-70`）。点一下大页面会明显卡窗口。

5. **`verifyPackageZip` 实际上没打开它要守的那个 zip**
   `build.gradle.kts:306-385`：`dependsOn(packageZip)`，但函数体只走 app-image 目录（`app/`、`.cfg`、`runtime/release`、launcher）。这次修的两类事故恰恰是**归档条目属性**（权限位丢失、根层多一层目录）。当前产物手工核对是对的，但「Zip 任务回归」这类问题 CI 仍会绿。建议在任务里解 `packageZip.flatMap { it.archiveFile }`，校验条目布局与 launcher 的 unix mode。

6. **toolchain 在配置期 `.get()` → 任何 gradle 命令都要求本机有 JDK 21**
   `build.gradle.kts:71-78` 的 `javaHome = jdk21Launcher.get()...` 在配置阶段求值，于是 `clean`、`tasks`、IDE sync 在只有 JDK 17/25 的机器上都会失败（单用 `jvmToolchain(21)` 只在真正编译时失败）。同期 `verifyPackageZip` 接受 `major >= 21`、README 写「≥ 21」，与配置期「必须正好 21」不一致。`runCatching { ... }.getOrNull()` 即可降级。

7. **今天的改动其实没跑过 CI**
   `.github/workflows/ci.yml:4-6` 只对 `push: [master]` 与 `pull_request` 触发，而今天的 6 个提交推在 `dev`（`origin/dev` 已更新）——新增的 CI 恰好没覆盖今天的实际工作分支。建议 `branches: [master, dev]`。

8. **AES 内容加密的「提前识别」是死代码**（代码核对，已读库源码）
   `ArchiveReader.kt:148-150` 判断 `e.contentMethods?.any { it.method == AES256SHA256 }`。commons-compress 1.28.0 只在解码路径设置它（`SevenZFile.java:787-790`），`getEntries()` 只是 `archive.files` 的拷贝（`:1012-1014`），头解析阶段永远不设，`:836` 的注释也写明「may be null」。后果：只加密内容、不加密文件名的 7z 不会弹密码框，而是每个条目各自失败——正是注释声明要避免的情况。修法：`password == null` 时主动读一下首个非目录条目并捕获 `PasswordRequiredException`。

9. **GIF 合成画布不受像素预算约束**
   `GifDecoder.kt:80` 用**逻辑画布尺寸**分配 `BufferedImage`（GIF 允许到 65535²），而 `MAX_TOTAL_PIXELS` 只约束输出帧；`restoreToPrevious` 还每帧 `copyOf` 一整张画布。5000² 即 100MB + 100MB 的预算外分配，65535² 直接尝试 17GB（OOM 会被 `runCatching` 吞掉 → 退化成静态解码 / 加载失败）。旧实现逐帧转换，没有这笔分配。修法：给画布尺寸设上限，或按 `scale` 直接在目标尺寸上合成。

## 4. P3

- `BookshelfScreen.kt:159` `remember(book.path)` 把「路径已失效」按路径缓存一次，拔/插移动硬盘后标记不会更新（key 换成 `book` 或 `book.path to book.lastRead`）。
- `GalleryScreen.kt:629` 解码上限写死 `coerceIn(160, 640)`，而新的 `THUMB_SIZE_MAX = 360f` dp（`Settings.kt:28`）在 density 2 就想取 720px（density 3 → 1080px），最大档会偏糊。
- `BookshelfScreen.kt:165-172`：深色主题下白字压在 `danger = #E57373`（`Theme.kt:66`）上约 3.0:1，不到 AA 的 4.5:1。
- `JsonStore.kt:50-60` `backupCorrupt` 忽略 `renameTo` 的返回值却照样打「已备份」日志——改名失败时坏文件仍会被下一次 save 覆盖，正是这个函数想避免的事。
- `App.kt:576-585` `passwordRequest` 在新加载开始时没被清掉，密码框能取消并顶掉一个更新的加载。
- `ArchiveReader.kt:60` vs `:106`：`readEntry` 是 `@Synchronized` 而 `close()` 不是。切图集时后台解码可能撞上已关闭的 `ZipFile` / LRU 迭代器，异常最终被 `runCatching` 吞掉（表现只是占位图 + 日志噪音）。建议 `close()` 也加锁。
- `ImageLoader.kt:114-117` `tmp.renameTo(target)`：Windows 上无法覆盖已存在的目标（实测 `renameTo` 返回 false 且目标保持 3 字节不变），坏掉的缓存条目永远修不好、每次都要重新解码再丢弃。改用 `Files.move(..., REPLACE_EXISTING)`（`JsonStore.kt:78` 已经这么写）。
- `ImageLoader.kt:91` `key.toByteArray()` 用平台默认字符集。不只是风格问题：若以 `-Dfile.encoding=GBK` 启动且路径含 GBK 不可表示的字符，编码会替换成 `?`，**不同 key 可能算出同一个 SHA-1 → 串图**。加 `Charsets.UTF_8`。
- `ImageLoader.kt:124-136` `prune()` 只删 `*.webp`，`writeBytes` 中途被杀留下的 `.tmp` 永远不会清理。
- `ImageLoader.kt:98` 每次读缓存都 `setLastModified`，即每个缩略图命中都产生一次文件系统元数据写。
- `ImageLoader.kt:202-216` 预取与翻页共用 `fullSemaphore(2)`，且没有 in-flight 去重：同一张图可能被解码两次，用户排在两个预取之后。
- `build.gradle.kts:291` `isPreserveFileTimestamps = true` 使发布 zip 不可复现；`build.gradle.kts:87-90` 的注释把「漏 `jdk.unsupported`」归因于「jlink 按探测结果打包」，实际是 Compose 插件里一份硬编码默认模块表（jdeps 建议只打印不生效）——结论对、理由错；`"ComposeGallery"` 字面量在 `:91` / `:220` / `:287` / `:314` 各写一遍，改 `packageName` 会让 packageZip 与 MSI/DEB 静默不一致。
- `.github/workflows/ci.yml:108` `softprops/action-gh-release@v2` 是可移动 tag，却处在 `contents: write` 的 job 里，建议钉 SHA。
- README 的 CI 小节只写了 `test` + `packageZip`，漏了 CI 实际会跑的 `verifyPackageZip`（`ci.yml:75`）与 Release job。

## 5. 既有问题（**不是今天引入的**，逐条与 `b25ae8e` 比对过）

- `ImageViewer.kt:197-199` `to100()` 是唯一不调 `clampToViewport()` 的缩放入口（旧版第 159 行一字不差）。后果实打实：4000×3000 图在 1000×800 视口放大到 8× 并拖到边缘后按 `1`，`offsetX` 仍是 −3500 而新上限只有 1500 → 图片整个飞出视口变成黑屏。修法一行。
- `ImageViewer.kt:711` `pointerInput(bitmap)` 配 `:393` 每帧新建的 `frames[frameIndex]`（旧版第 517/290 行相同）：动画 GIF 每 ~100ms 换 key → 手势协程重启 → `awaitEachGesture` 等新的 down → **播放中的 GIF 基本拖不动**。`zoom` 是 `remember { ZoomState() }` 稳定对象，key 直接换 `Unit` 即可。
- `GalleryScreen.kt:205` `key = { it.source.cacheKey }`：同名条目（zip 允许重名条目，追加式压缩包常见）会产生重复 key，`LazyVerticalGrid` 会直接抛 `IllegalArgumentException`。
- `ArchiveReader.kt:96-102` 的字节上限不是硬上限：`entryCache.size > 1` 守卫让单个超大条目可以常驻（最大 512MB）。
- `ImageScanner.kt:111` 与 `Model.kt:135` 都按**末段文件名**排序，多章压缩包（`ch1/001.jpg`、`ch2/001.jpg`）会章节交错。该 key 选择旧版就是 `it.name.lowercase()`，属既有问题；但今天的自然排序让它更显眼，值得一并考虑按条目全路径排序。

## 6. 测试缺口（已复核代码，成立）

- `GalleryCoreTest.kt:79` 首个断言可空过：`DiskThumbnailCache.dir` 是全局共享目录，前面的用例已经写过 `.webp`；同用例 `:81-86` 那半段（删掉源文件 + 清内存缓存后仍能解出并验像素）是好断言。
- `GalleryCoreTest.kt:667-681` 只设了 `disposalMethod="doNotDispose"`，所以今天修复真正实现的 `restoreToBackgroundColor` / `restoreToPrevious` 两个分支**零覆盖**（两个 `make*Gif` 辅助函数都确认过）。
- 没有任何 7z / cb7 用例——2.1 的 P0 就藏在这里。
- `:262-278` 等用例会改动共享的 store 目录并留下 `bookshelf.json` / `settings.json`，而其他用例会构造 `AppState()` → 存在顺序依赖。

## 7. 已验证正确的部分（核对过，不是推断）

- **两处 AGENTS.md 硬不变量**：所有 `folder`/`archive`/`archiveReader`/`images` 的写点都在 `commitLocation`（`App.kt:148-173`）或 `rescanCurrent` 里；IO 全在 `Dispatchers.IO`，扫描返回到同步提交之间没有挂起点，被取消的加载不可能「提交一半」。`updateProgress` 只有一个调用者 `saveCurrentProgress`，四个参数全显式传入，且总是在改 `locationPath` **之前**调用。
- **进度持久化**：`closeViewer` 先存后清、`Main.kt:37` 关窗补存、`App.kt:403-407` 延迟合并写盘三处都对；`progressKey`（图片路径）+ 下标兜底的恢复策略能跨排序 / 增删文件。`rescanCurrent` 不重置 `selectedIndex` 我一度怀疑有问题，核对后**不成立**：刷新入口（F5 / 工具栏 / 子文件夹开关）只在网格可用，此时 `selectedIndex == -1`；查看器打开时 F5 被忽略与今天之前完全一致。
- **自然排序的溢出防护**：去前导零后先比位数再逐位比，超长数字不溢出（违规只出在 Unicode 数字上，见 2.2）。
- **递归扫描的 worker/pending 协议**：子目录的 `incrementAndGet` 一定发生在父目录 `decrementAndGet` 之前，`pending == 0` 时不可能还有未入队的目录 → 不会丢唤醒或挂死。
- **GIF 差帧合成**：canvas 全尺寸 + `left/top` 偏移 + 三种 disposal 的处理顺序符合 GIF89a，且有 `animatedGifComposesPartialFrames` 这类真断言覆盖（差分块内外各验一个像素）。`anim.width/height` 报逻辑画布尺寸、帧是缩放后的尺寸，这是刻意的（只用于信息面板），与绘制用的实际 bitmap 尺寸不冲突。`reader.dispose()` + 流关闭移入 `finally` 修掉了一个真实泄漏。
- **i18n**：枚举把 zh/en 绑在一起，缺翻译是编译错误；18 个带参调用点逐个核对 `%s`/`%d` 的数量与类型（`total`/`visibleCount` 都是 `Int`），无格式串异常风险；今天删除的 key 已无引用、新增的 key 都有使用。
- **缩略图 `remember(source, targetPx)`**：`ImageSource` 未覆写 `equals`，刷新压缩包后新 `ArchiveSource` 实例会强制重试，注释所述行为属实。
- **`GifAnimation.loopCount` / 帧延时**：延时在 `GifDecoder.kt:132` 被钳到 ≥20ms，不会出现 0 延时忙循环；帧索引由 `% frames.size` 保证在界内。
- **不列入 avif 是对的**：打包内的 `skiko-windows-x64.dll` 里 0 个 avif 字符串（webp 1 个、jpeg 4 个）。
- **`zip.entries` 每次调用返回新枚举**，所以 `needsFallback` / `looksLikeShiftJis` / 构造器各遍历一次是安全的；「畸形 UTF-8 被 commons-compress 解成 `?`」这一前提成立（实测 `name="???/001.jpg"`），用 `rawName` + 严格 UTF-8 探测的思路是对的。
- **加密 zip 的处理**：`usesEncryption()` 读的是中央目录标志位，reader 在抛 `EncryptedZipException` 前已关闭，界面有明确提示。
- **`readBounded` + `use {}`** 限制了不可信大小并关闭流/归档；`ByteLruCache` 全部同步、替换时扣减旧值、按 eldest 先淘汰、单个超预算值时正确终止。
- **打包**：`gradlew` 的可执行位是**新增**（100644 → 100755，同一 blob），正是新 CI 能在 ubuntu 上跑起来的原因；`gradle/sandbox.gradle.kts` 只在 `-Psandbox` / `GRADLE_SANDBOX=1` 时被 apply，默认脚本里没有机器相关路径；`modules("jdk.unsupported")` 是**追加**而非替换默认模块表；`javaHome` 确实同时喂给 jlink 与 jpackage。
- **README 的功能声明**逐条抽查与代码一致（扩展名集合、数据目录、`.corrupt-<ts>` 备份、`-Dcompose.gallery.home`、`updateProgress` 四参数、logs 路径等）。

## 8. 附：本次审查过程中对沙箱 ACL 的修复

审查开始时**所有**命令（含只读 `git status`）都以 `SetNamedSecurityInfoW failed (Win32 5): grantWrite(D:\ai\gallery)` 失败——沙箱给工作区授权时被拒。按 `diagnose-windows-sandbox-acl` 技能诊断：判定 `PRECONDITION`，`D:\ai\gallery` 上调用方有 `WRITE_DAC` 但缺 `WRITE_OWNER`。随后用技能自带的 `-GrantFullControl -AllowRoot 'D:\ai'` 施加了**只针对该目录、只添加当前用户 allow ACE**（保留 owner / inheritance / SACL / deny）的修复，脚本自校验 `verified`，命令恢复正常。

回滚命令：

```powershell
pwsh -NoProfile -File 'D:\ai\dsh-acl-reports\acl-backup-39c24069f98f451f884f7000e1178d35.txt.ps1' -Path 'D:\ai\gallery' -AllowRoot 'D:\ai' -Restore 'D:\ai\dsh-acl-reports\acl-backup-39c24069f98f451f884f7000e1178d35.txt.json'
```

诊断报告与备份位于 `D:\ai\dsh-acl-reports\`。审查过程未改动仓库内任何文件（`git status` 干净），只新增了 `build/` 下的打包产物（已被 gitignore），临时探针程序已删除。

## 9. 建议的处理顺序

1. `implementation("org.tukaani:xz:1.10")` + 一个真实 7z/cb7 往返测试（否则今天宣称的压缩包能力有一半不可用）。
2. `naturalCompare` 数字段改成 ASCII 判定 + 契约/全角数字用例。
3. Shift-JIS 探测与回退改用 `windows-31j` + CP932 专有字符用例。
4. `DiskThumbnailCache.write` 改用 `Files.move(REPLACE_EXISTING)`；`ArchiveCoverSource.cacheKey` 补 mtime/size。
5. 修 Ctrl+滚轮写盘节流、`moveTick` 重组、归档条目外部打开移出 UI 线程。
6. 重做 / 删除恒为 false 的 AES 预检测；给 GIF 画布加预算上限。
7. 顺手补两个既有 P1：`to100()` 加 `clampToViewport()`、`pointerInput(bitmap)` 改 `Unit`。
8. CI 覆盖 `dev` 分支；`verifyPackageZip` 真正校验 zip；`javaHome` 配置期降级。
