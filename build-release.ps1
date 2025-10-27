param(
    [switch]$Open
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

$gradleTask = "assembleRelease"
Write-Host "Running gradle task: $gradleTask"
& .\gradlew.bat $gradleTask
if ($LASTEXITCODE -ne 0) {
    throw "Gradle release build failed."
}

$apkPath = Join-Path $scriptDir "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apkPath)) {
    throw "Release APK not found at $apkPath"
}

Write-Host "Release APK ready: $apkPath"

if ($Open) {
    Write-Host "Opening release folder..."
    Start-Process -FilePath (Split-Path $apkPath -Parent)
}
