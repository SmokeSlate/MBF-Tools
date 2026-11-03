# Run on Windows: pwsh -NoProfile -File tests/build-release.Tests.ps1
# No signing credentials, Android SDK, Gradle, or Pester are required.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$source = Join-Path (Split-Path $PSScriptRoot -Parent) 'build-release.ps1'
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw "Release script has parsing errors: $parseErrors" }
$function = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Invoke-GradleBuild'
}, $true)
Invoke-Expression $function.Extent.Text

$tempDir = Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
$originalLocation = Get-Location
$originalLocalAppData = $env:LOCALAPPDATA
try {
    New-Item -ItemType Directory $tempDir | Out-Null
    $stub = Join-Path $tempDir 'gradlew.bat'
    foreach ($exitCode in @(1, 0)) {
        "@echo off`r`necho Gradle progress line one`r`necho Gradle progress line two`r`nexit /b $exitCode" |
            Set-Content $stub -Encoding ascii
        $result = Invoke-GradleBuild -ProjectDir $tempDir -GradleTask 'testReleaseUnitTest lintRelease assembleRelease'
        if ($result -isnot [bool] -or $result -ne ($exitCode -eq 0)) {
            throw "Expected a scalar Boolean for exit code $exitCode."
        }
        if ((Get-Location).Path -ne $originalLocation.Path) { throw 'Location was not restored.' }
    }

    # Exercise the entire release script with stale outputs and a failing build.
    "@echo off`r`necho Gradle progress`r`necho Test failure`r`nexit /b 1" |
        Set-Content $stub -Encoding ascii
    Copy-Item $source (Join-Path $tempDir 'build-release.ps1')
    $env:LOCALAPPDATA = Join-Path $tempDir 'localappdata'
    $configDir = Join-Path $env:LOCALAPPDATA 'MBFTools\Signing'
    New-Item -ItemType Directory $configDir -Force | Out-Null
    $dummyKey = Join-Path $tempDir 'dummy-signing-file'
    Set-Content $dummyKey 'not a real signing key'
    [pscustomobject]@{
        LegacyKeystorePath = $dummyKey
        CurrentKeystorePath = $dummyKey
        LineagePath = $dummyKey
    } | Export-Clixml (Join-Path $configDir 'signing-config.clixml')
    $outputDir = Join-Path $tempDir 'app\build\outputs\apk\release'
    New-Item -ItemType Directory $outputDir -Force | Out-Null
    Set-Content (Join-Path $outputDir 'output-metadata.json') 'stale metadata must never be read'
    Set-Content (Join-Path $outputDir 'app-release-unsigned.apk') 'stale APK'
    $failure = $null
    try { & (Join-Path $tempDir 'build-release.ps1') } catch { $failure = $_ }
    if ($null -eq $failure -or $failure.Exception.Message -ne 'Release verification or build failed.') {
        throw "The release did not stop at the build-failure guard: $failure"
    }
    if (Test-Path (Join-Path $tempDir 'release')) { throw 'The signing stage was reached.' }
    Write-Host 'Release failure-guard regression tests passed.'
} finally {
    Set-Location $originalLocation
    $env:LOCALAPPDATA = $originalLocalAppData
    Remove-Item $tempDir -Recurse -Force
}

# The deliberately failing native fixture leaves LASTEXITCODE=1. Only report
# success after every assertion and cleanup above have completed.
exit 0
