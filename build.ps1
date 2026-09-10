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

& "D:\ai\.tools\gradle-9.7.1\bin\gradle.bat" -p "D:\ai\gallery" @args
exit $LASTEXITCODE
