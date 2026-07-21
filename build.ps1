$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
$version = (Get-Content -LiteralPath (Join-Path $projectRoot 'VERSION') -Raw).Trim()
$dist = Join-Path $projectRoot 'dist'

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

Push-Location (Join-Path $projectRoot 'bridge')
try {
    cargo fmt --all -- --check
    if ($LASTEXITCODE -ne 0) { throw 'cargo fmt failed' }
    cargo test --locked --offline
    if ($LASTEXITCODE -ne 0) { throw 'cargo test failed' }
    cargo build --release --locked --offline
    if ($LASTEXITCODE -ne 0) { throw 'cargo build failed' }
} finally {
    Pop-Location
}

Push-Location (Join-Path $projectRoot 'android')
try {
    & .\gradlew.bat :app:lintRelease :app:assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
} finally {
    Pop-Location
}

New-Item -ItemType Directory -Path $dist -Force | Out-Null
$apkName = "QuietPanel-v{0}.apk" -f $version
Get-ChildItem -LiteralPath $dist -Filter 'QuietPanel-v*.apk' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne $apkName } |
    Remove-Item -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'bridge\target\release\QuietPanelBridge.exe') `
    -Destination (Join-Path $dist 'QuietPanelBridge.exe') -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'android\app\build\outputs\apk\release\app-release.apk') `
    -Destination (Join-Path $dist $apkName) -Force

$adbPath = $env:QUIETPANEL_ADB
if ([string]::IsNullOrWhiteSpace($adbPath)) {
    $adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
}
if (-not (Test-Path -LiteralPath $adbPath)) {
    throw 'adb.exe not found. Set QUIETPANEL_ADB to its full path.'
}

$adbDir = Split-Path -Parent $adbPath
foreach ($file in @('adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll')) {
    $source = Join-Path $adbDir $file
    if (-not (Test-Path -LiteralPath $source)) {
        throw "Required ADB file not found: $source"
    }
    Copy-IfDifferent -Source $source -Destination (Join-Path $dist $file)
}

Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging\Start-QuietPanel.cmd') -Destination $dist -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging\Install-Android.cmd') -Destination $dist -Force

$hashFiles = Get-ChildItem -LiteralPath $dist -File |
    Where-Object { $_.Name -ne 'SHA256SUMS.txt' } |
    Sort-Object Name
$hashLines = foreach ($file in $hashFiles) {
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    "$hash  $($file.Name)"
}
Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Value $hashLines -Encoding ascii

Write-Output "Built QuietPanel v$version"
Get-ChildItem -LiteralPath $dist -File | Select-Object Name, Length, LastWriteTime
