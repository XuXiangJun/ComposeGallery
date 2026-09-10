# Build helper: pins JDK 21 + redirects temp / gradle home into the workspace,
# which the DSH sandbox permits writing to.
$ErrorActionPreference = "Continue"

$env:JAVA_HOME = "D:\ai\.tools\jdk21\jdk-21.0.12+8"
$env:GRADLE_USER_HOME = "D:\ai\.tools\gradle-home"
$env:TMP = "D:\ai\.tools\tmp"
$env:TEMP = "D:\ai\.tools\tmp"
$env:TMPDIR = "D:\ai\.tools\tmp"

New-Item -ItemType Directory -Force -Path $env:TMP | Out-Null
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME | Out-Null

# Use the project's Gradle wrapper so the Gradle version is defined by
# gradle/wrapper/gradle-wrapper.properties instead of a machine-local install.
& "$PSScriptRoot\gradlew.bat" -p "$PSScriptRoot" @args
exit $LASTEXITCODE
