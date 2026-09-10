# Build helper for the DSH sandbox: pins JDK 21 and redirects temp / gradle home
# into D:\ai\.tools, which the sandbox permits writing to.
#
# 每个沙箱路径都只在存在时才生效：换到普通 Windows 机器（没有 D:\ai\.tools）时这些变量
# 不会被设置，脚本就退化成"用项目自带 wrapper 跑 Gradle"，无需改动。
$ErrorActionPreference = "Continue"

$sandboxRoot = "D:\ai\.tools"
$sandboxJdk = "$sandboxRoot\jdk21\jdk-21.0.12+8"
if (Test-Path $sandboxJdk) { $env:JAVA_HOME = $sandboxJdk }

if (Test-Path $sandboxRoot) {
    $env:GRADLE_USER_HOME = "$sandboxRoot\gradle-home"
    $env:TMP = "$sandboxRoot\tmp"
    $env:TEMP = "$sandboxRoot\tmp"
    $env:TMPDIR = "$sandboxRoot\tmp"
    New-Item -ItemType Directory -Force -Path $env:TMP | Out-Null
    New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME | Out-Null
}

# Use the project's Gradle wrapper so the Gradle version is defined by
# gradle/wrapper/gradle-wrapper.properties instead of a machine-local install.
& "$PSScriptRoot\gradlew.bat" -p "$PSScriptRoot" @args
exit $LASTEXITCODE
