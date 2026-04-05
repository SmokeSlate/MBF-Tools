param(
    [string]$Variant = "debug"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-GradleBuild {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ProjectDir,
        [Parameter(Mandatory = $true)]
        [string]$GradleTask,
        [switch]$NoDaemon
    )

    Push-Location $ProjectDir
    try {
        $gradleArgs = @("gradlew.bat")
        if ($NoDaemon) {
            $gradleArgs += "--no-daemon"
        }
        $gradleArgs += "--configuration-cache"
        $gradleArgs += $GradleTask

        $process = Start-Process `
            -FilePath "cmd.exe" `
            -ArgumentList "/c", ($gradleArgs -join " ") `
            -WorkingDirectory $ProjectDir `
            -NoNewWindow `
            -Wait `
            -PassThru
        return ($process.ExitCode -eq 0)
    } finally {
        Pop-Location
    }
}

function Stop-GradleDaemons {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ProjectDir
    )

    Push-Location $ProjectDir
    try {
        & .\gradlew.bat --stop | Out-Host
    } finally {
        Pop-Location
    }
}

function New-BuildMirror {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SourceDir,
        [Parameter(Mandatory = $true)]
        [string]$Variant
    )

    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $mirrorDir = Join-Path $env:TEMP "QuestDevSettings-build-$Variant-$timestamp"
    New-Item -ItemType Directory -Path $mirrorDir | Out-Null

    $robocopyArgs = @(
        $SourceDir,
        $mirrorDir,
        "/MIR",
        "/XD",
        ".git",
        ".gradle",
        "build",
        "app\build"
    )

    & robocopy @robocopyArgs | Out-Null
    if ($LASTEXITCODE -gt 7) {
        throw "Failed to create temp build mirror."
    }

    return $mirrorDir
}

function Resolve-AdbPath {
    $sdkAdbPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $sdkAdbPath) {
        return $sdkAdbPath
    }

    return "adb"
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

$capitalizedVariant = $Variant.Substring(0, 1).ToUpper() + $Variant.Substring(1)
$gradleTask = "assemble$capitalizedVariant"
$apkName = "app-$Variant.apk"
$workspaceApkPath = Join-Path $scriptDir "app\build\outputs\apk\$Variant\$apkName"

$buildRoot = $scriptDir
$apkPath = $workspaceApkPath

Write-Host "Running gradle task: $gradleTask"
if (-not (Invoke-GradleBuild -ProjectDir $scriptDir -GradleTask $gradleTask)) {
    Write-Warning "Workspace build failed. Retrying from a clean temp copy."
    Stop-GradleDaemons -ProjectDir $scriptDir

    $buildRoot = New-BuildMirror -SourceDir $scriptDir -Variant $Variant
    $apkPath = Join-Path $buildRoot "app\build\outputs\apk\$Variant\$apkName"

    Write-Host "Running gradle task in temp copy: $buildRoot"
    if (-not (Invoke-GradleBuild -ProjectDir $buildRoot -GradleTask $gradleTask -NoDaemon)) {
        throw "Gradle build failed in both the workspace and temp copy (task $gradleTask)."
    }
}

if (-not (Test-Path $apkPath)) {
    throw "APK not found at $apkPath. Verify the variant name."
}

$adbPath = Resolve-AdbPath

Write-Host "Checking for connected devices..."
$devicesOutput = & $adbPath devices
if ($LASTEXITCODE -ne 0) {
    throw "Failed to run adb devices."
}

$connectedDevices = @($devicesOutput -split "`n" | Where-Object { $_ -match "\tdevice$" })
if ($connectedDevices.Count -eq 0) {
    throw "No adb device detected. Connect your headset and enable USB debugging."
}

Write-Host "Installing $apkPath"
& $adbPath install -r $apkPath
if ($LASTEXITCODE -ne 0) {
    throw "adb install failed."
}

Write-Host "Build and install complete."
