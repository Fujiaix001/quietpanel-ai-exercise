$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
$dist = Join-Path $projectRoot 'dist'
$version = (Get-Content -LiteralPath (Join-Path $projectRoot 'VERSION') -Raw).Trim()

function Copy-IfDifferent {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][string]$Path)

    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        return ([System.BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-', '')
    } finally {
        $stream.Dispose()
        $algorithm.Dispose()
    }
}

# 1. Rust Bridge Tests & Release Build (USB ADB only)
Push-Location (Join-Path $projectRoot 'bridge')
try {
    cargo fmt --all -- --check
    if ($LASTEXITCODE -ne 0) { throw 'cargo fmt failed' }

    Write-Output "Testing Rust Bridge..."
    cargo test --locked
    if ($LASTEXITCODE -ne 0) { throw 'cargo test failed' }

    Write-Output "Building Rust Bridge v$version (USB ADB)..."
    cargo build --release
    if ($LASTEXITCODE -ne 0) { throw 'cargo build ADB failed' }
} finally {
    Pop-Location
}

# 2. Android App Build
Push-Location (Join-Path $projectRoot 'android')
try {
    Write-Output "Building Android APK v$version..."
    & .\gradlew.bat :app:assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
} finally {
    Pop-Location
}

# 3. Assemble Dist
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$bridgeExe = Join-Path $projectRoot 'bridge\target\release\QuietPanelBridge.exe'
Copy-IfDifferent -Source $bridgeExe -Destination (Join-Path $dist "QuietPanelBridge-v$version.exe")
Copy-IfDifferent -Source $bridgeExe -Destination (Join-Path $dist 'QuietPanelBridge.exe')

$builtApk = Join-Path $projectRoot 'android\app\build\outputs\apk\release\app-release.apk'
Copy-Item -LiteralPath $builtApk -Destination (Join-Path $dist "QuietPanel-v$version.apk") -Force

# 4. ADB Tools
$adbPath = $env:QUIETPANEL_ADB
if ([string]::IsNullOrWhiteSpace($adbPath)) {
    $adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
}
if (Test-Path -LiteralPath $adbPath) {
    $adbDir = Split-Path -Parent $adbPath
    foreach ($file in @('adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll')) {
        $source = Join-Path $adbDir $file
        if (Test-Path -LiteralPath $source) {
            $destination = Join-Path $dist $file
            if (-not (Test-Path -LiteralPath $destination)) {
                Copy-IfDifferent -Source $source -Destination $destination
            }
        }
    }
}

# 5. Copy ADB launch scripts only
foreach ($file in @('Install-Android-v6-ADB.cmd', 'Start-QuietPanel-v6-ADB.cmd')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot "packaging\\$file") -Destination $dist -Force
}

# 6. Generate Checksums
$hashFiles = Get-ChildItem -LiteralPath $dist -File |
    Where-Object { $_.Name -ne 'SHA256SUMS.txt' } |
    Sort-Object Name
$hashLines = foreach ($file in $hashFiles) {
    $hash = Get-Sha256Hex -Path $file.FullName
    "$hash  $($file.Name)"
}
Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Value $hashLines -Encoding ascii

Write-Output "Successfully built QuietPanel v$version (USB ADB)!"
Get-ChildItem -LiteralPath $dist -File | Select-Object Name, Length, LastWriteTime
