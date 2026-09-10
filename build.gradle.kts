import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.bundling.Jar
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.0"
}

group = "com.example"
version = "1.0.0"

// 平台判定：jpackage 的可执行文件名、图标格式与分发包格式在各 OS 上不同。
val osName = System.getProperty("os.name").lowercase()
val isWindows = osName.startsWith("windows")
val isMac = osName.startsWith("mac") || osName.startsWith("darwin")
val isLinux = !isWindows && !isMac

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.apache.commons:commons-compress:1.28.0")
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

// Wrap WiX light.exe so it appends "-sval" (skip ICE validation). ICE validation
// requires the Windows Installer Service, which is unavailable in the sandbox and
// makes light.exe exit with 216. Runs after unzipWix, before packageMsi.
val wrapWixLight = tasks.register("wrapWixLight") {
    group = "build"
    description = "Wrap WiX light.exe to skip ICE validation"
    dependsOn("unzipWix")
    doLast {
        val wixDir = file("build/wix311")
        val lightExe = File(wixDir, "light.exe")
        val realDir = File(wixDir, "real")
        // WiX 缓存位置跟随实际的 GRADLE_USER_HOME（compose 插件把 WiX 下到这里）。
        val wixZip = File(gradle.gradleUserHomeDir, "compose-jb/wix311.zip")
        val src = file("packaging/LightWrapper.cs")
        if (!lightExe.exists()) {
            logger.lifecycle("light.exe missing; skipping wrapper install")
            return@doLast
        }
        // 非沙箱机器：WiX 缓存不在本机路径，且 ICE 校验通常能通过，跳过包装。
        if (!wixZip.exists()) {
            logger.lifecycle("wix311.zip not found (non-sandbox); skipping light.exe wrapper - ICE validation should pass normally")
            return@doLast
        }

        // Extract the full original WiX toolset into real/ — light.exe depends on
        // several sibling DLLs, not just wix.dll, and must keep its original name.
        realDir.mkdirs()
        ZipFile(wixZip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val target = File(realDir, e.name)
                target.parentFile?.mkdirs()
                zf.getInputStream(e).use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var n = input.read(buf)
                        while (n > 0) {
                            output.write(buf, 0, n)
                            n = input.read(buf)
                        }
                    }
                }
            }
        }

        // Overwrite build/wix311/light.exe with the wrapper.
        val csc = "C:\\Windows\\Microsoft.NET\\Framework64\\v4.0.30319\\csc.exe"
        val pb = ProcessBuilder(csc, "/nologo", "/out:${lightExe.absolutePath}", src.absolutePath)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        val proc = pb.start()
        val code = proc.waitFor()
        check(code == 0) { "csc failed with exit code $code" }
        logger.lifecycle("light.exe wrapper installed")
    }
}

// The DSH sandbox's default temp dir may not exist by the time a child JVM starts,
// The DSH sandbox may not have a writable default temp dir and blocks user.home/.skiko.
// Only redirect when the sandbox workspace dir exists; on normal machines use defaults.
tasks.withType<JavaExec>().configureEach {
    val sandboxTmp = File("D:\\ai\\.tools\\tmp")
    if (sandboxTmp.exists()) {
        systemProperty("java.io.tmpdir", sandboxTmp.absolutePath)
        systemProperty("skiko.data.path", "D:\\ai\\.tools\\skiko")
        environment("TMP", sandboxTmp.absolutePath)
        environment("TEMP", sandboxTmp.absolutePath)
    }
}

compose.desktop {
    application {
        mainClass = "gallery.MainKt"

        nativeDistributions {
            // 分发包格式按平台选择：Windows -> MSI，macOS -> DMG，其余（Linux）-> DEB。
            when {
                isWindows -> targetFormats(TargetFormat.Msi)
                isMac -> targetFormats(TargetFormat.Dmg)
                else -> targetFormats(TargetFormat.Deb)
            }
            packageName = "ComposeGallery"
            packageVersion = "1.0.0"
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

// packageMsi 任务由 compose.desktop 块惰性创建，用 matching 惰性匹配。
tasks.matching { it.name == "packageMsi" }.configureEach {
    dependsOn(wrapWixLight)
}

// packageZip 里的 jpackage 取自 toolchain 指定的 JDK 21：Gradle daemon 可能跑在系统其它 JDK
// （例如 JDK 25）上，而 runtime image 是由 toolchain 21 生成的，JDK 不匹配时 jpackage 会失败（exit 1）。
val jdk21Launcher = javaToolchains.launcherFor {
    languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21))
}

// 免安装 zip 版：jpackage --type app-image（不需要 WiX），再打包成 zip。
val packageZip = tasks.register("packageZip") {
    group = "build"
    description = "Build a portable zip distribution (jpackage app-image)"
    dependsOn("createRuntimeImage", "jar", "unpackDefaultComposeDesktopJvmApplicationResources")
    doLast {
        val buildDirFile = layout.buildDirectory.asFile.get()
        val libsDir = File(buildDirFile, "compose/tmp/packageZip/libs")
        libsDir.deleteRecursively()
        libsDir.mkdirs()

        // 依赖 jar + 主 jar
        configurations.getByName("runtimeClasspath").files.forEach { f ->
            if (f.isFile && f.name.endsWith(".jar")) {
                f.copyTo(File(libsDir, f.name), overwrite = true)
            }
        }
        val mainJar = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        mainJar.copyTo(File(libsDir, mainJar.name), overwrite = true)

        // skiko 原生库 + icudtl.dat（Compose 已解压到 tmp/skiko）
        File(buildDirFile, "compose/tmp/skiko").listFiles()?.forEach { f ->
            if (f.isFile) f.copyTo(File(libsDir, f.name), overwrite = true)
        }

        // 用 toolchain 的 JDK 21 里的 jpackage（见文件上方 jdk21Launcher 的说明）。
        val jpHome = jdk21Launcher.get().metadata.installationPath.asFile
        // Windows 上是 jpackage.exe，Linux / macOS 上是无扩展名的 jpackage。
        val jpackageName = if (isWindows) "jpackage.exe" else "jpackage"
        val jpackage = File(jpHome, "bin/$jpackageName").absolutePath
        val runtimeImage = File(buildDirFile, "compose/tmp/main/runtime")
        val destDir = File(buildDirFile, "compose/binaries/main/app")
        destDir.mkdirs()
        val appName = "ComposeGallery"
        // macOS 的 app-image 是 ComposeGallery.app 目录，Windows / Linux 是无扩展名的同名目录。
        val appImageDirName = if (isMac) "$appName.app" else appName
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
            "--runtime-image", runtimeImage.absolutePath,
            "--main-jar", mainJar.name,
            "--main-class", "gallery.MainKt",
            "--name", appName,
            "--app-version", version.toString(),
            "--vendor", "Compose Gallery",
            "--description", "Compose Gallery - image viewer & comic bookshelf",
            "--dest", destDir.absolutePath,
            "--java-options", "-Dskiko.library.path=\$APPDIR",
            "--java-options", "-Dcompose.application.configure.swing.globals=true",
        )
        // --icon 只在 Windows 上传：macOS 的 app-image 只接受 .icns（仓库暂无），
        // Linux 的 app-image 不使用图标参数。
        if (isWindows) {
            jpackageArgs += listOf("--icon", File(projectDir, "src/main/resources/icon.ico").absolutePath)
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
            val logText = if (isWindows) String(jpackageLog.readBytes(), charset("GB18030")) else jpackageLog.readText()
            "jpackage app-image failed with exit $code\n" +
                "--- jpackage output (${jpackageLog.absolutePath}) ---\n" +
                logText.takeLast(4000)
        }

        // 打包成 zip
        val appDir = File(destDir, appImageDirName)
        val zipFile = File(destDir, "ComposeGallery-${version}.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            appDir.walkTopDown().forEach { f ->
                val rel = appDir.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                if (f.isDirectory) {
                    zos.putNextEntry(ZipEntry("$rel/"))
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { input ->
                        val buf = ByteArray(8192)
                        var n = input.read(buf)
                        while (n > 0) {
                            zos.write(buf, 0, n)
                            n = input.read(buf)
                        }
                    }
                    zos.closeEntry()
                }
            }
        }
        logger.lifecycle("portable zip written to ${zipFile.absolutePath} (${zipFile.length() / 1024 / 1024} MB)")
    }
}
