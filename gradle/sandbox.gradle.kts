// 受限环境（沙箱）专用的构建辅助逻辑。
//
// 默认构建路径【不】加载本文件 —— build.gradle.kts 只在显式开启时才 apply 它：
//   ./gradlew -Psandbox packageMsi        （macOS / Linux）
//   .\gradlew.bat -Psandbox packageMsi    （Windows）
//   GRADLE_SANDBOX=1 ./gradlew packageMsi （等效）
//
// 之所以抽出来：这些东西都指向某台沙箱主机的路径 / 依赖沙箱里特有的服务状态，
// 放在 build.gradle.kts 里意味着每个 clone 仓库的人都要继承它们。默认构建在任何
// 机器上都应当可复现。
//
// 本文件做三件事：
//   1. 把 JavaExec（./gradlew run）的临时目录与 skiko 数据目录指到沙箱里可写的位置。
//   2. 包装 WiX 的 light.exe，链接时追加 -sval 跳过 ICE 校验。
//   3. 让 packageMsi 依赖上面的包装任务。
//
// 普通 Windows 机器上打包完全不需要这些：WiX 3 会照常自动下载，ICE 校验也能通过。
// 不要把这里的内容搬回 build.gradle.kts。

import java.util.zip.ZipFile

// 1) JavaExec 临时目录重定向。
//    路径不存在时（在普通机器上误开了 -Psandbox）保持原样，不强改。
val sandboxTmp = File("D:\\ai\\.tools\\tmp")
val sandboxSkiko = File("D:\\ai\\.tools\\skiko")
if (sandboxTmp.isDirectory) {
    tasks.withType<JavaExec>().configureEach {
        systemProperty("java.io.tmpdir", sandboxTmp.absolutePath)
        systemProperty("skiko.data.path", sandboxSkiko.absolutePath)
        environment("TMP", sandboxTmp.absolutePath)
        environment("TEMP", sandboxTmp.absolutePath)
    }
}

// 2) 包装 light.exe。
//    ICE 校验需要访问 Windows Installer Service，沙箱里该服务不可用，light.exe 会 exit 216。
//    configuration cache 不允许任务执行期访问 project / gradle，所以路径都在配置期抓成普通值。
val wrapWixLight = tasks.register("wrapWixLight") {
    group = "build"
    description = "Wrap WiX light.exe to skip ICE validation (sandbox builds only)"
    dependsOn("unzipWix")

    val wixDir = file("build/wix311")
    val lightExe = File(wixDir, "light.exe")
    val realDir = File(wixDir, "real")
    // WiX 缓存位置跟随实际的 GRADLE_USER_HOME（compose 插件把 WiX 下到这里）。
    val wixZip = File(gradle.gradleUserHomeDir, "compose-jb/wix311.zip")
    val wrapperSrc = file("packaging/LightWrapper.cs")
    val csc = "C:\\Windows\\Microsoft.NET\\Framework64\\v4.0.30319\\csc.exe"

    doLast {
        if (!lightExe.exists()) {
            logger.lifecycle("light.exe missing; skipping wrapper install")
            return@doLast
        }
        // 非沙箱机器：WiX 缓存不在本机路径，且 ICE 校验通常能通过，跳过包装。
        if (!wixZip.exists()) {
            logger.lifecycle("wix311.zip not found (non-sandbox); skipping light.exe wrapper - ICE validation should pass normally")
            return@doLast
        }

        // 解压完整 WiX 工具集到 real/ —— light.exe 依赖多个同级 DLL（不止 wix.dll），
        // 且必须保持原文件名。
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

        // 用包装器覆盖 build/wix311/light.exe。
        val pb = ProcessBuilder(csc, "/nologo", "/out:${lightExe.absolutePath}", wrapperSrc.absolutePath)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        val proc = pb.start()
        val code = proc.waitFor()
        check(code == 0) { "csc failed with exit code $code" }
        logger.lifecycle("light.exe wrapper installed")
    }
}

// 3) packageMsi 由 compose.desktop 块惰性创建，用 matching 惰性匹配。
tasks.matching { it.name == "packageMsi" }.configureEach {
    dependsOn(wrapWixLight)
}
