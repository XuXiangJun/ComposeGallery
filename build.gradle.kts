import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.bundling.Jar
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
}

// 受限环境（沙箱）的构建辅助逻辑不参与默认构建路径：临时目录重定向、WiX light.exe 包装等
// 都指向某台沙箱主机，放在这里意味着每个 clone 仓库的人都要继承它们。默认构建在任何机器上
// 都应可复现。需要时显式开启：
//   ./gradlew -Psandbox packageMsi       （macOS / Linux）
//   .\gradlew.bat -Psandbox packageMsi   （Windows）
//   GRADLE_SANDBOX=1 与 -Psandbox 等效
// 详见 gradle/sandbox.gradle.kts 顶部的说明。
val sandboxBuild = providers.gradleProperty("sandbox").isPresent ||
    System.getenv("GRADLE_SANDBOX") == "1"
if (sandboxBuild) {
    apply(from = "gradle/sandbox.gradle.kts")
}

group = "com.github.xuxiangjun"
version = "1.0.0"

// 平台判定：jpackage 的可执行文件名、图标格式与分发包格式在各 OS 上不同。
val osName = System.getProperty("os.name").lowercase()
val isWindows = osName.startsWith("windows")
val isMac = osName.startsWith("mac") || osName.startsWith("darwin")
val isLinux = !isWindows && !isMac

// 应用名：MSI / DEB 的 packageName、packageZip 的 app-image 目录 / launcher / .cfg 名都用它。
// 原先四处各写一遍字面量，改了 packageName 就会让 packageZip 与 MSI/DEB 静默不一致。
val appBaseName = "ComposeGallery"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    // UI 组件全部来自 Material 2（androidx.compose.material.*，见 ui/ 下的 import）——
    // 没有一处用到 material3，所以不声明该依赖。要迁到 Material3 时记得把
    // org.jetbrains.compose.material3:material3 加回来（注意 JetBrains 侧它的稳定版
    // 线最高只到 1.9.0，之后是 alpha，别顺手升预发布版）。
    implementation("org.jetbrains.compose.material:material:1.12.1")
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.apache.commons:commons-compress:1.28.0")
    // commons-compress 把 xz 标成 optional，但 7z 解码（SevenZFile → Coders 的静态块）
    // 无条件引用 org.tukaani.xz：缺了它所有 7z / cb7 一打开就 NoClassDefFoundError。
    implementation("org.tukaani:xz:1.10")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(kotlin("test-junit5"))
    // Align the whole JUnit 5 stack (jupiter + platform); kotlin-test-junit5
    // only brings 5.10.1 / 1.10.1 transitively.
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// 打包统一用 toolchain 指定的 JDK 21，而不是「跑 Gradle 的那个 JDK」：
// - Compose 的 createRuntimeImage（jlink）默认取 Gradle 所在 JVM。Gradle 跑在 JDK 17 上时，
//   打出来的 runtime 是 17，而类文件按 21 编译（class version 65）—— 构建全绿，一启动就
//   UnsupportedClassVersionError。所以下面显式设 javaHome；
// - packageZip 里的 jpackage 也必须和 runtime image 同版本，否则 jpackage 失败（exit 1）。
val jdk21Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

compose.desktop {
    application {
        mainClass = "gallery.MainKt"
        // 这一行在配置期求值：本机没有 JDK 21 时直接 .get() 会让 clean / tasks / IDE 同步全部失败。
        // 降级成 Gradle 自身的 JDK，只影响打包；打出的 runtime 版本不对时 verifyPackageZip 会拦下。
        javaHome = runCatching { jdk21Launcher.get().metadata.installationPath.asFile.absolutePath }
            .getOrElse {
                logger.warn("找不到 JDK 21 toolchain（${it.message?.lineSequence()?.firstOrNull()}），打包将使用 ${System.getProperty("java.home")}")
                System.getProperty("java.home")
            }

        nativeDistributions {
            // 分发包格式按平台选择：Windows -> MSI，macOS -> DMG，其余（Linux）-> DEB。
            when {
                isWindows -> targetFormats(TargetFormat.Msi)
                isMac -> targetFormats(TargetFormat.Dmg)
                else -> targetFormats(TargetFormat.Deb)
            }
            // Compose 插件的 jlink 用的是插件内置的一份默认模块表，并不按依赖探测
            // （suggestRuntimeModules 只打印建议、不生效），表里没有 jdk.unsupported（sun.misc.Unsafe）。
            // Gson 反序列化没有无参构造器的 Kotlin 类（BookEntry / RecentEntry）全靠它：缺了它，
            // 打包版每次启动读书架 / 最近打开都会失败 —— 单元测试跑在完整 JDK 上，发现不了。
            modules("jdk.unsupported")
            packageName = appBaseName
            packageVersion = version.toString()
            description = "Compose Gallery - image viewer & comic bookshelf"
            vendor = "Compose Gallery"
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
            // TODO(macOS)：DMG 需要 .icns，仓库暂无该资源（app-image/zip 打包时已按平台跳过 --icon）。
            //   备好 src/main/resources/icon.icns 后打开下面一行：
            //   macOS { iconFile.set(project.file("src/main/resources/icon.icns")) }
            // TODO(Linux)：DEB 需要 512x512 PNG 图标，仓库暂无该资源。
            //   备好 src/main/resources/icon.png 后打开下面一行：
            //   linux { iconFile.set(project.file("src/main/resources/icon.png")) }
        }
    }
}


// 免安装 zip 版分两步：先 jpackage --type app-image（不需要 WiX），再由下面的 packageZip
// （Gradle 自带的 Zip 任务）打成 zip。
val createPortableAppImage = tasks.register("createPortableAppImage") {
    group = "build"
    description = "Build the jpackage app-image used by packageZip"
    dependsOn("createRuntimeImage", "jar", "unpackDefaultComposeDesktopJvmApplicationResources")

    // configuration cache 要求任务执行期不访问 project / configurations / tasks，
    // 因此这些都在配置期解析成普通值（File / Pair<File, String> / String）后捕获进任务。
    val buildDirFile = layout.buildDirectory.get().asFile
    // 注意：在这个 tasks.register 作用域里，`group` / `name` 指的是**任务**的 group 和 name，
    // 不是项目的（曾经因此把主 jar 命名成 build_packageZip_1.0.0.jar）。项目身份要显式取。
    val projectGroup = project.group.toString()
    val projectName = project.name

    // runtime classpath 上的 jar，以及它应当落到 libs 目录里的**唯一**文件名。
    //
    // 不能直接用 jar 自己的文件名：jpackage 会把 --input 里的 jar 平铺进一个目录、再据此
    // 生成 app.classpath。而 Compose 的 runtimeClasspath 里存在 groupId 不同、基名却
    // 完全相同的 artifact，例如
    //   androidx.compose.runtime:runtime-saveable-desktop:1.12.1     （35 条目，真有类）
    //   org.jetbrains.compose.runtime:runtime-saveable-desktop:1.12.1（ 9 条目，只有 LICENSE）
    // 按原名拷进同一目录必然互相覆盖，后拷的把先拷的空壳盖上去 —— 打包阶段 BUILD SUCCESSFUL，
    // 一运行就 NoClassDefFoundError: androidx/compose/runtime/saveable/SaverScope。
    // 所以输出名带上 group，并校验全局无重名，把这类问题变成构建期失败。
    val runtimeJarEntries: List<Pair<File, String>> =
        configurations.getByName("runtimeClasspath").incoming.artifactView {
            isLenient = true
        }.artifacts.mapNotNull { artifact ->
            val f = artifact.file
            if (!f.isFile || !f.name.endsWith(".jar")) return@mapNotNull null
            val module = artifact.id.componentIdentifier as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
            val target = if (module != null) {
                "${module.group}_${module.module}_${module.version}.jar"
            } else {
                // 项目内 artifact 没有 module 坐标，退回原名（下面会校验无重名）
                f.name
            }
            f to target
        }

    val mainJarName = "${projectGroup}_${projectName}_$version.jar"
    val mainJar = tasks.named<Jar>("jar").get().archiveFile.get().asFile
    val runtimeImageDir = File(buildDirFile, "compose/tmp/main/runtime")
    val destDir = File(buildDirFile, "compose/binaries/main/app")
    val appIcon = file("src/main/resources/icon.ico")
    val jpackageHome = jdk21Launcher.get().metadata.installationPath.asFile
    val win = isWindows
    val mac = isMac
    val appVersion = version.toString()
    val appName = appBaseName

    doLast {
        // 先把重名检查做在拷贝之前：等 jpackage 生成完 .cfg 才发现缺类就太晚了。
        val allTargets = runtimeJarEntries.map { it.second } + mainJarName
        val duplicates = allTargets.groupBy { it }.filterValues { it.size > 1 }.keys
        check(duplicates.isEmpty()) {
            "runtime classpath 上有多个 jar 会落到同一个文件名：$duplicates。" +
                "它们会在 libs 目录里互相覆盖，导致打出来的程序缺类。" +
                "请给 build.gradle.kts 的输出名规则加上更多区分维度。"
        }

        val libsDir = File(buildDirFile, "compose/tmp/packageZip/libs")
        libsDir.deleteRecursively()
        libsDir.mkdirs()

        // 依赖 jar + 主 jar（用唯一文件名，见上方说明）
        runtimeJarEntries.forEach { (src, target) ->
            src.copyTo(File(libsDir, target), overwrite = true)
        }
        mainJar.copyTo(File(libsDir, mainJarName), overwrite = true)

        // skiko 原生库（skiko-windows-x64.dll / skiko-linux-x64.so / icudtl.dat）。
        //
        // 不能依赖 `build/compose/tmp/skiko`：Compose 1.12.x 已不再把原生库解压到那里，
        // 该目录不存在时上面那段 listFiles()?.forEach 会静默地什么都不做 —— 结果是 jar 明明
        // 都在、程序一启动就 LibraryLoadException（缺 skiko-windows-x64.dll），且构建本身
        // 显示 BUILD SUCCESSFUL，很难排查。
        //
        // 改为直接从 classpath 上的 skiko-awt-runtime-<平台> jar 里把原生资源取出来。
        // 这些 jar 已经随 runtimeJars 拷进 libsDir 了，而 .cfg 里 app.classpath=$APPDIR\xxx.jar
        // 说明 $APPDIR 正是 libsDir 对应的 app/ 目录 —— 而 -Dskiko.library.path=$APPDIR
        // 会让 skiko 到这个目录找 .dll。两边对上，缺了它就一定起不来。
        val skikoNativeNames = mutableListOf<String>()
        runtimeJarEntries.filter { it.first.name.startsWith("skiko-awt-runtime-") }.forEach { (jar, _) ->
            ZipFile(jar).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    // 只取顶层的原生资源（skiko-windows-x64.dll / icudtl.dat / .sha256），跳过 META-INF。
                    if (entry.isDirectory || entry.name.contains('/')) continue
                    val out = File(libsDir, entry.name)
                    zf.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    skikoNativeNames += entry.name
                }
            }
        }
        // 构建期就失败，而不是让用户拿到一个起不来的包。
        check(skikoNativeNames.isNotEmpty()) {
            "classpath 上找不到 skiko 原生库 jar（skiko-awt-runtime-*），" +
                "打包出的程序会因缺少 skiko 原生库而无法启动。请检查 compose.desktop.currentOs 是否生效。"
        }
        logger.lifecycle("skiko 原生库已放进 app 目录（即 java-options 里 skiko.library.path 指向处）：${skikoNativeNames.joinToString(", ")}")

        // 用 toolchain 的 JDK 21 里的 jpackage（见上方 jdk21Launcher 的说明）。
        // Windows 上是 jpackage.exe，Linux / macOS 上是无扩展名的 jpackage。
        val jpackageName = if (win) "jpackage.exe" else "jpackage"
        val jpackage = File(jpackageHome, "bin/$jpackageName").absolutePath

        destDir.mkdirs()
        // macOS 的 app-image 是 ComposeGallery.app 目录，Windows / Linux 是无扩展名的同名目录。
        val appImageDirName = if (mac) "$appName.app" else appName
        // 清理上次生成的 app image，否则 jpackage 会因目录已存在而失败。
        // 注意：jpackage 生成的 launcher（Windows 上是 ComposeGallery.exe）带只读属性，
        // File.deleteRecursively() 碰到只读文件会静默失败并留下目录，导致下一次打包报
        // 「应用程序目标目录已存在」。所以先递归清掉只读属性，再删除并校验结果。
        val appImageDir = File(destDir, appImageDirName)
        if (appImageDir.exists()) {
            appImageDir.walkBottomUp().toList().forEach { it.setWritable(true) }
            check(appImageDir.deleteRecursively()) {
                "无法清理上一次的 app image 目录：${appImageDir.absolutePath}（可能仍被占用，请手动删除后重试）"
            }
        }

        val jpackageArgs = mutableListOf(
            jpackage,
            "--type", "app-image",
            "--input", libsDir.absolutePath,
            "--runtime-image", runtimeImageDir.absolutePath,
            "--main-jar", mainJarName,
            "--main-class", "gallery.MainKt",
            "--name", appName,
            "--app-version", appVersion,
            "--vendor", "Compose Gallery",
            "--description", "Compose Gallery - image viewer & comic bookshelf",
            "--dest", destDir.absolutePath,
            "--java-options", "-Dskiko.library.path=\$APPDIR",
            "--java-options", "-Dcompose.application.configure.swing.globals=true",
            // JDK 24+ 会警告/阻止未经 native-access 声明的 System.load（skiko 加载原生库就走这条）。
            // 在 JDK 21 上只是告警，先声明掉，免得每次启动都刷几行 WARNING。
            "--java-options", "--enable-native-access=ALL-UNNAMED",
        )
        // --icon 只在 Windows 上传：macOS 的 app-image 只接受 .icns（仓库暂无），
        // Linux 的 app-image 不使用图标参数。
        if (win) {
            jpackageArgs += listOf("--icon", appIcon.absolutePath)
        }
        val pb = ProcessBuilder(jpackageArgs)
        pb.redirectErrorStream(true)
        // 捕获 jpackage 的输出：INHERIT 会让它的报错落进 Gradle daemon 日志而不可见，
        // 打包失败时无从排查。
        val jpackageLog = File(buildDirFile, "compose/tmp/packageZip/jpackage.log")
        jpackageLog.parentFile?.mkdirs()
        pb.redirectOutput(jpackageLog)
        val proc = pb.start()
        val code = proc.waitFor()
        check(code == 0) {
            // jpackage 在中文 Windows 上按本地编码（GBK/GB18030）输出，按 UTF-8 读会乱码。
            // charset(...) 是 kotlin.text 的顶层函数，无需 import（写 java.nio.charset.Charset
            // 全限定名会失败：脚本里的 java 指向 Gradle 的 java 扩展）。
            val logText = if (win) String(jpackageLog.readBytes(), charset("GB18030")) else jpackageLog.readText()
            "jpackage app-image failed with exit $code\n" +
                "--- jpackage output (${jpackageLog.absolutePath}) ---\n" +
                logText.takeLast(4000)
        }
    }
}

// 打 zip 用 Gradle 自带的 Zip 任务，而不是手写 ZipOutputStream：后者不写 Unix 权限位，
// Linux 上解压出来的 bin/ComposeGallery 是 644，双击 / 命令行都起不来。Zip 任务会把文件
// 原有的权限（jpackage 生成的 launcher 是 755）记进 zip 条目里。
val packageZip = tasks.register<Zip>("packageZip") {
    group = "build"
    description = "Build a portable zip distribution (jpackage app-image)"
    dependsOn(createPortableAppImage)
    val destDir = layout.buildDirectory.dir("compose/binaries/main/app")
    val appImageDirName = if (isMac) "$appBaseName.app" else appBaseName
    from(destDir.map { it.dir(appImageDirName) })
    destinationDirectory.set(destDir)
    archiveFileName.set("$appBaseName-$version.zip")
    // 可复现：同样的输入打出逐字节相同的 zip（条目时间戳固定、顺序固定），便于校验发布产物。
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    // Gradle 9 起归档任务默认写固定权限（文件一律 644），launcher 的可执行位会丢；
    // 这里显式改回「沿用文件系统上的权限」。
    useFileSystemPermissions()
    doLast {
        val zip = archiveFile.get().asFile
        logger.lifecycle("portable zip written to ${zip.absolutePath} (${zip.length() / 1024 / 1024} MB)")
    }
}

// 任务动作里不能碰 project（配置缓存），读 zip 用注入的 ArchiveOperations。
interface InjectedArchiveOps {
    @get:javax.inject.Inject
    val archiveOps: ArchiveOperations
}

// 打包产物自检：把「打包 BUILD SUCCESSFUL、程序一运行就崩」这类问题变成构建失败。
// 真实发生过两次，packageZip 都是绿的：
//   1) skiko 原生库没进 app/            -> LibraryLoadException
//   2) 同基名 jar 互相覆盖（空壳赢）     -> NoClassDefFoundError
// 前四项查的是 app image 目录；第 5 项打开 zip 本身，查 Zip 任务这一步（根层多一层目录、
// launcher 可执行位丢失 —— 这两类也真实发生过）。
// jpackage 只关心 --input 里有没有 jar，这两类缺失它一律不管，所以必须自己查。
val verifyPackageZip = tasks.register("verifyPackageZip") {
    group = "verification"
    description = "Verify the packageZip app image contains what its launcher needs"
    dependsOn(packageZip)
    val win0 = isWindows

    // 配置期抓成普通值，执行期不碰 project。
    val destDirFile = File(layout.buildDirectory.get().asFile, "compose/binaries/main/app")
    val appName0 = appBaseName
    val mac0 = isMac
    val zipFileProvider = packageZip.flatMap { it.archiveFile }
    val archiveOps = objects.newInstance<InjectedArchiveOps>().archiveOps

    doLast {
        val appImageDir = File(destDirFile, if (mac0) "$appName0.app" else appName0)
        // jpackage app-image 的 app 目录位置因平台而异：Windows 是 app/，Linux 是 lib/app/，
        // macOS 是 Contents/app/。（原先写死 app/，在 Linux 上这一步永远过不了。）
        val appDir = listOf("app", "lib/app", "Contents/app").map { File(appImageDir, it) }
            .firstOrNull { it.isDirectory }
        check(appDir != null) { "找不到 app 目录（app / lib/app / Contents/app）：${appImageDir.absolutePath}" }

        // 1) skiko 原生库必须真的躺在 app/ —— java-options 里 -Dskiko.library.path=$APPDIR
        //    指的就是这里，skiko 加载不到就直接 LibraryLoadException。
        // Windows 上是 skiko-windows-x64.dll，Linux / macOS 上带 lib 前缀（libskiko-linux-x64.so）。
        val natives = appDir.listFiles { f ->
            f.isFile && (f.name.startsWith("skiko-") || f.name.startsWith("libskiko-")) &&
                !f.name.endsWith(".jar") && !f.name.endsWith(".sha256")
        }?.toList().orEmpty()
        check(natives.isNotEmpty()) {
            "app/ 里没有任何 skiko 原生库（skiko-windows-x64.dll / skiko-linux-x64.so 等），" +
                "程序启动时会 LibraryLoadException。请检查 build.gradle.kts 中从 " +
                "skiko-awt-runtime-* 提取原生库的逻辑。"
        }
        val emptyNatives = natives.filter { it.length() == 0L }
        check(emptyNatives.isEmpty()) {
            "以下原生库大小为 0 字节：${emptyNatives.map { it.name }}"
        }

        // 2) .cfg 里引用的 classpath 必须都真实存在。缺失说明往 app/ 拷 jar 时丢了东西
        //    （最可能是同基名互相覆盖）。
        val cfg = File(appDir, "$appName0.cfg")
        check(cfg.isFile) { "找不到启动配置文件：${cfg.absolutePath}" }
        val appDirMarker = "\$APPDIR"
        val missing = cfg.readLines().mapNotNull { line ->
            if (!line.startsWith("app.classpath=")) return@mapNotNull null
            val rel = line.removePrefix("app.classpath=")
                .replace(appDirMarker, "")
                .trim('\\', '/', ' ')
            if (rel.isEmpty()) null else File(appDir, rel).takeIf { !it.isFile }
        }
        check(missing.isEmpty()) {
            "$appName0.cfg 引用了 ${missing.size} 个不存在的 jar，" +
                "说明拷贝阶段丢了文件（很可能是同基名 jar 互相覆盖）：" +
                missing.take(5).joinToString { it.name }
        }

        // 3) 打进去的 runtime 必须是 JDK 21+（类文件按 21 编译），且包含 Gson 需要的 jdk.unsupported。
        val release = listOf("runtime/release", "lib/runtime/release", "Contents/runtime/Contents/Home/release")
            .map { File(appImageDir, it) }.firstOrNull { it.isFile }
        check(release != null) { "找不到 runtime/release：${appImageDir.absolutePath}" }
        val props = release.readLines().associate { line ->
            line.substringBefore('=') to line.substringAfter('=', "").trim('"')
        }
        val major = props["JAVA_VERSION"].orEmpty().substringBefore('.').toIntOrNull() ?: 0
        check(major >= 21) {
            "打包的 runtime 是 Java ${props["JAVA_VERSION"]}，但类文件按 21 编译，启动会 UnsupportedClassVersionError。" +
                "检查 compose.desktop.application.javaHome。"
        }
        check("jdk.unsupported" in props["MODULES"].orEmpty().split(' ')) {
            "runtime 缺 jdk.unsupported 模块，Gson 读不了书架 / 设置。检查 nativeDistributions.modules。"
        }

        // 4) 非 Windows 上 launcher 必须可执行（zip 里的权限位取自这里）。
        if (!win0) {
            val launcher = File(appImageDir, if (mac0) "Contents/MacOS/$appName0" else "bin/$appName0")
            check(launcher.isFile && launcher.canExecute()) {
                "launcher 不存在或没有可执行权限：${launcher.absolutePath}"
            }
        }

        // 5) zip 本身：条目与 app image 目录逐一对应（根层直接是 app image 的内容，不多套一层目录、
        //    不漏文件），且非 Windows 上 launcher 条目带可执行位。
        val zipFile = zipFileProvider.get().asFile
        val zipModes = mutableMapOf<String, Int>()
        archiveOps.zipTree(zipFile).visit {
            if (!isDirectory) zipModes[relativePath.pathString] = permissions.toUnixNumeric()
        }
        val expectedPaths = appImageDir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(appImageDir).invariantSeparatorsPath }.toSet()
        val missingInZip = expectedPaths - zipModes.keys
        val extraInZip = zipModes.keys - expectedPaths
        check(missingInZip.isEmpty() && extraInZip.isEmpty()) {
            "${zipFile.name} 与 app image 目录不一致：缺 ${missingInZip.size} 个（${missingInZip.take(5)}），" +
                "多 ${extraInZip.size} 个（${extraInZip.take(5)}）。检查 packageZip 的 from(...) 布局。"
        }
        if (!win0) {
            val launcherEntry = if (mac0) "Contents/MacOS/$appName0" else "bin/$appName0"
            val mode = zipModes.getValue(launcherEntry)
            check(mode and 0b001_000_000 != 0) {
                "${zipFile.name} 里 $launcherEntry 的权限是 ${Integer.toOctalString(mode)}，没有可执行位，" +
                    "解压后起不来。检查 packageZip 的 useFileSystemPermissions()。"
            }
        }

        logger.lifecycle("verifyPackageZip: OK（skiko 原生库 ${natives.joinToString { it.name }}；classpath 条目全部存在；" +
            "zip ${zipModes.size} 个条目与 app image 一致）")
    }
}
