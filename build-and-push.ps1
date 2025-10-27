param(
    [string]$Variant = "debug"
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

$capitalizedVariant = $Variant.Substring(0, 1).ToUpper() + $Variant.Substring(1)
$gradleTask = "assemble$capitalizedVariant"

Write-Host "Running gradle task: $gradleTask"
& .\gradlew.bat $gradleTask
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed (task $gradleTask)."
}

$adbPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adbPath)) {
    $adbPath = "adb"
}

Write-Host "Checking for connected devices..."
$devicesOutput = & $adbPath devices
if ($LASTEXITCODE -ne 0) {
    throw "Failed to run adb devices."
}

$connectedDevices = $devicesOutput -split "`n" | Where-Object { $_ -match "\tdevice$" }
if ($connectedDevices.Count -eq 0) {
    throw "No adb device detected. Connect your headset and enable USB debugging."
}

$apkName = "app-$Variant.apk"
$apkPath = Join-Path $scriptDir "app\build\outputs\apk\$Variant\$apkName"
if (-not (Test-Path $apkPath)) {
    throw "APK not found at $apkPath. Verify the variant name."
}

Write-Host "Installing $apkPath"
& $adbPath install -r $apkPath
if ($LASTEXITCODE -ne 0) {
    throw "adb install failed."
}

Write-Host "Build and install complete."
