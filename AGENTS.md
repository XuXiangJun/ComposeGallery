- 编译和测试统一用项目自带的 Gradle Wrapper：macOS/Linux 用 `./gradlew`，Windows 用 `.\gradlew.bat`
- 默认构建脚本（`build.gradle.kts`）只放可移植逻辑，不指向任何特定机器；沙箱专用的辅助逻辑
  （临时目录重定向、WiX `light.exe` 包装）收在 `gradle/sandbox.gradle.kts`，需要时显式开启：
  `./gradlew -Psandbox packageMsi` 或 `GRADLE_SANDBOX=1`
- 改动持久化（书架 / 设置）时注意：`AppState.updateProgress()` 的参数（id / 下标 / 总数 /
  当前图片路径）必须全部显式传入，不能在切换图集之后依赖隐式的当前位置；`JsonStore` 的原子写是
  「临时文件 + move」，别改回 `writeText`（见 `AppState` 与 `JsonStore` 的注释）
- 图集加载一律走 `App.kt` 的 `launchLoad`：新加载会取消旧的，IO 做完后再由 `commitLocation`
  一次性改 state，别在挂起点之前改 `folder` / `archive` / `images`
- 打包相关的两个坑已由 `verifyPackageZip` 把关：runtime 必须是 JDK 21（`javaHome` 指向 toolchain），
  且要带 `jdk.unsupported`（Gson 依赖它反序列化 `BookEntry` / `RecentEntry`）
