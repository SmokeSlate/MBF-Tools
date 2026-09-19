Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-PropertiesFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    $result = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
        $parts = $trimmed.Split('=', 2)
        if ($parts.Count -eq 2) {
            $result[$parts[0].Trim()] = $parts[1].Trim()
        }
    }
    return $result
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$legacyPropertiesPath = Join-Path $scriptDir "app\signing.properties"
if (-not (Test-Path -LiteralPath $legacyPropertiesPath)) {
    throw "The legacy signing properties were not found; a lineage can only be created while the previous key is available."
}

$legacy = Get-PropertiesFile -Path $legacyPropertiesPath
foreach ($requiredName in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
    if (-not $legacy.ContainsKey($requiredName) -or -not $legacy[$requiredName]) {
        throw "Legacy signing properties are missing $requiredName."
    }
}

$legacyKeystoreSource = [IO.Path]::GetFullPath(
    (Join-Path (Split-Path $legacyPropertiesPath -Parent) $legacy['storeFile'])
)
if (-not (Test-Path -LiteralPath $legacyKeystoreSource)) {
    throw "The legacy keystore was not found."
}

$signingDir = Join-Path $env:LOCALAPPDATA "MBFTools\Signing"
$configPath = Join-Path $signingDir "signing-config.clixml"
$legacyKeystorePath = Join-Path $signingDir "mbftools-release-legacy.jks"
$currentKeystorePath = Join-Path $signingDir "mbftools-release-v2.p12"
$lineagePath = Join-Path $signingDir "mbftools-signing-lineage.bin"
if (Test-Path -LiteralPath $configPath) {
    throw "Protected release signing is already configured at $configPath; refusing to rotate again."
}

New-Item -ItemType Directory -Path $signingDir -Force | Out-Null
foreach ($targetPath in @($legacyKeystorePath, $currentKeystorePath, $lineagePath)) {
    if (Test-Path -LiteralPath $targetPath) {
        throw "Signing setup target already exists: $targetPath"
    }
}

$sdkLine = Get-Content -LiteralPath (Join-Path $scriptDir "local.properties") |
    Where-Object { $_ -match '^sdk\.dir=' } |
    Select-Object -First 1
if (-not $sdkLine) { throw "Android SDK path is missing from local.properties." }
$sdkDir = ($sdkLine -replace '^sdk\.dir=', '') -replace '\\\\', '\'
$buildToolsDir = Get-ChildItem -LiteralPath (Join-Path $sdkDir "build-tools") -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
$apkSignerPath = Join-Path $buildToolsDir.FullName "apksigner.bat"
$keytoolPath = (Get-Command keytool -ErrorAction Stop).Source

$newAlias = "mbftools-release-v2"
$newPassword = [Convert]::ToHexString(
    [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
).ToLowerInvariant()

Copy-Item -LiteralPath $legacyKeystoreSource -Destination $legacyKeystorePath
try {
    & $keytoolPath -genkeypair `
        -keystore $currentKeystorePath `
        -storetype PKCS12 `
        -storepass $newPassword `
        -keypass $newPassword `
        -alias $newAlias `
        -keyalg RSA `
        -keysize 4096 `
        -sigalg SHA256withRSA `
        -validity 10000 `
        -dname "CN=MBF Tools and Setup, OU=sm0ke, O=sm0ke, L=Unknown, ST=Unknown, C=US"
    if ($LASTEXITCODE -ne 0) { throw "New release key generation failed." }

    $env:MBF_OLD_STORE_PASSWORD = $legacy['storePassword']
    $env:MBF_OLD_KEY_PASSWORD = $legacy['keyPassword']
    $env:MBF_NEW_STORE_PASSWORD = $newPassword
    $env:MBF_NEW_KEY_PASSWORD = $newPassword
    try {
        & $apkSignerPath rotate `
            --out $lineagePath `
            --old-signer `
            --ks $legacyKeystorePath `
            --ks-key-alias $legacy['keyAlias'] `
            --ks-pass env:MBF_OLD_STORE_PASSWORD `
            --key-pass env:MBF_OLD_KEY_PASSWORD `
            --set-installed-data true `
            --set-shared-uid false `
            --set-permission false `
            --set-rollback false `
            --set-auth false `
            --new-signer `
            --ks $currentKeystorePath `
            --ks-key-alias $newAlias `
            --ks-pass env:MBF_NEW_STORE_PASSWORD `
            --key-pass env:MBF_NEW_KEY_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw "Signing lineage creation failed." }
    } finally {
        Remove-Item Env:MBF_OLD_STORE_PASSWORD, Env:MBF_OLD_KEY_PASSWORD,
            Env:MBF_NEW_STORE_PASSWORD, Env:MBF_NEW_KEY_PASSWORD -ErrorAction SilentlyContinue
    }

    $certificatePath = Join-Path $signingDir "current-certificate.der"
    & $keytoolPath -exportcert `
        -keystore $currentKeystorePath `
        -storetype PKCS12 `
        -storepass $newPassword `
        -alias $newAlias `
        -file $certificatePath
    if ($LASTEXITCODE -ne 0) { throw "Could not export the new signing certificate." }
    $currentSignerSha256 = (Get-FileHash -LiteralPath $certificatePath -Algorithm SHA256).Hash.ToLowerInvariant()
    Remove-Item -LiteralPath $certificatePath

    $config = [PSCustomObject]@{
        Version = 2
        LegacyKeystorePath = $legacyKeystorePath
        LegacyKeyAlias = $legacy['keyAlias']
        LegacyStorePassword = ConvertTo-SecureString $legacy['storePassword'] -AsPlainText -Force
        LegacyKeyPassword = ConvertTo-SecureString $legacy['keyPassword'] -AsPlainText -Force
        CurrentKeystorePath = $currentKeystorePath
        CurrentKeyAlias = $newAlias
        CurrentStorePassword = ConvertTo-SecureString $newPassword -AsPlainText -Force
        CurrentKeyPassword = ConvertTo-SecureString $newPassword -AsPlainText -Force
        CurrentSignerSha256 = $currentSignerSha256
        LineagePath = $lineagePath
        RotationMinSdkVersion = 28
    }
    $config | Export-Clixml -LiteralPath $configPath

    & $apkSignerPath lineage --in $lineagePath --print-certs -v
    if ($LASTEXITCODE -ne 0) { throw "Signing lineage verification failed." }

    Write-Host "Signing key rotation complete."
    Write-Host "Protected configuration: $configPath"
    Write-Host "New signer SHA-256: $currentSignerSha256"
} catch {
    foreach ($createdPath in @($configPath, $lineagePath, $currentKeystorePath, $legacyKeystorePath)) {
        if (Test-Path -LiteralPath $createdPath) {
            Remove-Item -LiteralPath $createdPath -Force
        }
    }
    throw
} finally {
    $newPassword = $null
}
