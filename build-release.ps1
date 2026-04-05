param(
    [switch]$Open
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-GradleBuild {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ProjectDir,
        [Parameter(Mandatory = $true)]
        [string]$GradleTask
    )

    Push-Location $ProjectDir
    try {
        $process = Start-Process `
            -FilePath "cmd.exe" `
            -ArgumentList "/c", "gradlew.bat --configuration-cache $GradleTask" `
            -WorkingDirectory $ProjectDir `
            -NoNewWindow `
            -Wait `
            -PassThru
        return ($process.ExitCode -eq 0)
    } finally {
        Pop-Location
    }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

$gradleTask = "assembleRelease"
Write-Host "Running gradle task: $gradleTask"
if (-not (Invoke-GradleBuild -ProjectDir $scriptDir -GradleTask $gradleTask)) {
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
