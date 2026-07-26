$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
$dist = Join-Path $projectRoot 'dist'
$version = (Get-Content -LiteralPath (Join-Path $projectRoot 'VERSION') -Raw).Trim()

function Copy-IfDifferent {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    if (Test-Path -LiteralPath $Destination) {
        $sourceHash = (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash
        $destinationHash = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash
        if ($sourceHash -eq $destinationHash) {
            return
        }
    }

    Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

# 1. Rust Bridge Tests & Release Build (Wi-Fi + Bluetooth Dual Mode)
Push-Location (Join-Path $projectRoot 'bridge')
try {
    cargo fmt --all -- --check
    if ($LASTEXITCODE -ne 0) { throw 'cargo fmt failed' }

    Write-Output "Testing Rust Bridge..."
    cargo test --locked --all-features
    if ($LASTEXITCODE -ne 0) { throw 'cargo test failed' }

    Write-Output "Building Rust Bridge v$version (Wi-Fi + Bluetooth Dual Mode)..."
    cargo build --release
    if ($LASTEXITCODE -ne 0) { throw 'cargo build wifi failed' }
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
$settingsPath = Join-Path $dist 'QuietPanelBridge.json'
if (-not (Test-Path -LiteralPath $settingsPath)) {
    @{
        bluetooth_device = '68:DF:DD:0C:C1:AE'
        phone_ip = '192.168.44.1'
        enabledPages = @(0, 1, 2, 3, 4, 5, 6)
    } | ConvertTo-Json | Set-Content -LiteralPath $settingsPath -Encoding utf8
}

$wifiExeTemp = Join-Path $projectRoot 'bridge\target\release\QuietPanelBridge.exe'
Copy-IfDifferent -Source $wifiExeTemp -Destination (Join-Path $dist "QuietPanelBridge-v$version.exe")
Copy-IfDifferent -Source $wifiExeTemp -Destination (Join-Path $dist 'QuietPanelBridge.exe')

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
            Copy-IfDifferent -Source $source -Destination (Join-Path $dist $file)
        }
    }
}

# 5. Copy Launch Scripts
Get-ChildItem -LiteralPath (Join-Path $projectRoot 'packaging') -Filter '*.cmd' | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $dist -Force
}

foreach ($file in @('Setup-Bluetooth-PAN.ps1', 'Setup-Bluetooth-PAN.cmd')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $file) -Destination $dist -Force
}

# 6. Generate Checksums
$hashFiles = Get-ChildItem -LiteralPath $dist -File |
    Where-Object { $_.Name -ne 'SHA256SUMS.txt' } |
    Sort-Object Name
$hashLines = foreach ($file in $hashFiles) {
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    "$hash  $($file.Name)"
}
Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Value $hashLines -Encoding ascii

Write-Output "Successfully built QuietPanel v$version (Wi-Fi + Bluetooth Dual Mode)!"
Get-ChildItem -LiteralPath $dist -File | Select-Object Name, Length, LastWriteTime
