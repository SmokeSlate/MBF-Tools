param(
    [switch]$Open
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-GradleBuild {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectDir,
        [Parameter(Mandatory = $true)][string]$GradleTask
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

function ConvertTo-PlainText {
    param([Parameter(Mandatory = $true)][Security.SecureString]$SecureValue)
    return [Net.NetworkCredential]::new('', $SecureValue).Password
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

$signingConfigPath = Join-Path $env:LOCALAPPDATA "MBFTools\Signing\signing-config.clixml"
if (-not (Test-Path -LiteralPath $signingConfigPath)) {
    throw "Protected release signing is not configured. Run .\setup-release-signing.ps1 first."
}
$signing = Import-Clixml -LiteralPath $signingConfigPath
foreach ($requiredPath in @($signing.LegacyKeystorePath, $signing.CurrentKeystorePath, $signing.LineagePath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "A required protected signing file is missing: $requiredPath"
    }
}

$gradleTask = "testReleaseUnitTest lintRelease assembleRelease"
Write-Host "Running gradle task: $gradleTask"
if (-not (Invoke-GradleBuild -ProjectDir $scriptDir -GradleTask $gradleTask)) {
    throw "Release verification or build failed."
}

$metadataPath = Join-Path $scriptDir "app\build\outputs\apk\release\output-metadata.json"
if (-not (Test-Path -LiteralPath $metadataPath)) {
    throw "Release metadata not found at $metadataPath"
}
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
$releaseElement = $metadata.elements | Select-Object -First 1
if (-not $releaseElement -or -not $releaseElement.versionName -or -not $releaseElement.outputFile) {
    throw "Release metadata did not contain a versioned APK."
}

$unsignedApkPath = Join-Path (Split-Path $metadataPath -Parent) $releaseElement.outputFile
if (-not (Test-Path -LiteralPath $unsignedApkPath)) {
    throw "Unsigned release APK not found at $unsignedApkPath"
}

$localPropertiesPath = Join-Path $scriptDir "local.properties"
$sdkLine = Get-Content -LiteralPath $localPropertiesPath |
    Where-Object { $_ -match '^sdk\.dir=' } |
    Select-Object -First 1
if (-not $sdkLine) { throw "Android SDK path is missing from local.properties." }
$sdkDir = ($sdkLine -replace '^sdk\.dir=', '') -replace '\\\\', '\'
$buildToolsDir = Get-ChildItem -LiteralPath (Join-Path $sdkDir "build-tools") -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
$apkSignerPath = Join-Path $buildToolsDir.FullName "apksigner.bat"
if (-not (Test-Path -LiteralPath $apkSignerPath)) {
    throw "apksigner was not found in the Android SDK build tools."
}

$releaseDir = Join-Path $scriptDir "release"
New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
$releaseFileName = "MBF-Tools-and-Setup-v$($releaseElement.versionName)-release.apk"
$releaseApkPath = Join-Path $releaseDir $releaseFileName
if (Test-Path -LiteralPath $releaseApkPath) {
    Remove-Item -LiteralPath $releaseApkPath -Force
}

$env:MBF_OLD_STORE_PASSWORD = ConvertTo-PlainText $signing.LegacyStorePassword
$env:MBF_OLD_KEY_PASSWORD = ConvertTo-PlainText $signing.LegacyKeyPassword
$env:MBF_NEW_STORE_PASSWORD = ConvertTo-PlainText $signing.CurrentStorePassword
$env:MBF_NEW_KEY_PASSWORD = ConvertTo-PlainText $signing.CurrentKeyPassword
try {
    $signArguments = @(
        'sign',
        '--out', $releaseApkPath,
        '--lineage', $signing.LineagePath,
        '--rotation-min-sdk-version', [string]$signing.RotationMinSdkVersion,
        '--v4-signing-enabled', 'false',
        '--ks', $signing.LegacyKeystorePath,
        '--ks-key-alias', $signing.LegacyKeyAlias,
        '--ks-pass', 'env:MBF_OLD_STORE_PASSWORD',
        '--key-pass', 'env:MBF_OLD_KEY_PASSWORD',
        '--next-signer',
        '--ks', $signing.CurrentKeystorePath,
        '--ks-key-alias', $signing.CurrentKeyAlias,
        '--ks-pass', 'env:MBF_NEW_STORE_PASSWORD',
        '--key-pass', 'env:MBF_NEW_KEY_PASSWORD',
        $unsignedApkPath
    )
    & $apkSignerPath @signArguments
    if ($LASTEXITCODE -ne 0) { throw "Rotated APK signing failed." }
} finally {
    Remove-Item Env:MBF_OLD_STORE_PASSWORD, Env:MBF_OLD_KEY_PASSWORD,
        Env:MBF_NEW_STORE_PASSWORD, Env:MBF_NEW_KEY_PASSWORD -ErrorAction SilentlyContinue
}

$verificationOutput = & $apkSignerPath verify --min-sdk-version 21 --verbose --print-certs $releaseApkPath 2>&1
if ($LASTEXITCODE -ne 0) {
    $verificationOutput | Write-Host
    throw "APK signature verification failed."
}
$verificationText = $verificationOutput -join "`n"
if ($verificationText -notmatch 'Verified using v2 scheme.*true' -or
        $verificationText -notmatch 'Verified using v3 scheme.*true') {
    $verificationOutput | Write-Host
    throw "The APK does not contain both compatibility and rotated signatures."
}
$actualSignerSha256 = $verificationOutput |
    ForEach-Object {
        if ($_ -match '^Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]+)') {
            $matches[1].ToLowerInvariant()
        }
    }
if ($actualSignerSha256 -notcontains $signing.CurrentSignerSha256.ToLowerInvariant()) {
    $verificationOutput | Write-Host
    throw "The rotated APK does not contain the expected current signing certificate."
}

$checksum = (Get-FileHash -LiteralPath $releaseApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$checksumPath = "$releaseApkPath.sha256"
"$checksum  $releaseFileName" | Set-Content -LiteralPath $checksumPath -Encoding ascii

Write-Host "Release APK ready: $releaseApkPath"
Write-Host "Current signer SHA-256: $($signing.CurrentSignerSha256)"
Write-Host "SHA-256: $checksum"
Write-Host "Checksum file: $checksumPath"

if ($Open) {
    Write-Host "Opening release folder..."
    Start-Process -FilePath $releaseDir
}
