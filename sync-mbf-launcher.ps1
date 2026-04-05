param(
    [string]$SourceDir
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

if (-not $SourceDir) {
    $SourceDir = Join-Path $env:TEMP "mbf-launcher-sync"

    if (Test-Path $SourceDir) {
        git -C $SourceDir fetch origin
        git -C $SourceDir reset --hard origin/master
    }
    else {
        git clone --depth 1 https://github.com/DanTheMan827/mbf-launcher.git $SourceDir
    }

    git -C $SourceDir submodule update --init --depth 1
}

$files = @(
    @{
        Source = Join-Path $SourceDir "MBF Launcher\libs\arm64-v8a\libMbfBridge.so"
        Target = Join-Path $scriptDir "app\src\main\jniLibs\arm64-v8a\libMbfBridge.so"
    },
    @{
        Source = Join-Path $SourceDir "MBF Launcher\libs\arm64-v8a\libAdbFinder.so"
        Target = Join-Path $scriptDir "app\src\main\jniLibs\arm64-v8a\libAdbFinder.so"
    },
    @{
        Source = Join-Path $SourceDir "MBF Launcher\libs\x86_64\libMbfBridge.so"
        Target = Join-Path $scriptDir "app\src\main\jniLibs\x86_64\libMbfBridge.so"
    },
    @{
        Source = Join-Path $SourceDir "MBF Launcher\libs\x86_64\libAdbFinder.so"
        Target = Join-Path $scriptDir "app\src\main\jniLibs\x86_64\libAdbFinder.so"
    },
    @{
        Source = Join-Path $SourceDir "OnDeviceADB\libs\arm64-v8a\libadb.so"
        Target = Join-Path $scriptDir "app\src\main\jniLibs\arm64-v8a\libadb.so"
    },
    @{
        Source = Join-Path $SourceDir "OnDeviceADB\libs\x86_64\libadb.so"
        Target = Join-Path $scriptDir "app\src\main\jniLibs\x86_64\libadb.so"
    }
)

foreach ($file in $files) {
    if (-not (Test-Path $file.Source)) {
        throw "Missing upstream file: $($file.Source)"
    }

    $targetDir = Split-Path -Parent $file.Target
    New-Item -ItemType Directory -Force $targetDir | Out-Null
    Copy-Item $file.Source $file.Target -Force
    Write-Host "Copied $($file.Source) -> $($file.Target)"
}

Write-Host "MBF launcher native assets refreshed from upstream."
