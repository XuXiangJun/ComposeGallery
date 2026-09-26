- 编译和测试统一用项目自带的 Gradle Wrapper：macOS/Linux 用 `./gradlew`，Windows 用 `.\gradlew.bat`
- 默认构建脚本（`build.gradle.kts`）只放可移植逻辑，不指向任何特定机器；沙箱专用的辅助逻辑
  （临时目录重定向、WiX `light.exe` 包装）收在 `gradle/sandbox.gradle.kts`，需要时显式开启：
  `./gradlew -Psandbox packageMsi` 或 `GRADLE_SANDBOX=1`
- 改动持久化（书架 / 设置）时注意：`AppState.updateProgress()` 的三个参数必须显式传入，
  不能在切换图集之后依赖隐式的当前位置；`JsonStore` 的原子写是「临时文件 + move」，别改回
  `writeText`（见 `AppState` 与 `JsonStore` 的注释）
