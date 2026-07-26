[CmdletBinding()]
param(
    [switch]$RestoreDhcp,
    [string]$PhoneIp = '192.168.44.1',
    [string]$ComputerIp = '192.168.44.2',
    [ValidateRange(1, 32)][int]$PrefixLength = 24
)

$ErrorActionPreference = 'Stop'

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not (Test-Administrator)) {
    $arguments = @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', ('"{0}"' -f $PSCommandPath)
    )
    if ($RestoreDhcp) {
        $arguments += '-RestoreDhcp'
    } else {
        $arguments += @(
            '-PhoneIp', $PhoneIp,
            '-ComputerIp', $ComputerIp,
            '-PrefixLength', $PrefixLength
        )
    }
    Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList $arguments -Wait
    exit $LASTEXITCODE
}

$panAdapters = @(Get-NetAdapter -IncludeHidden | Where-Object {
    $_.InterfaceDescription -like '*Personal Area Network*' -or
    $_.InterfaceDescription -like '*Bluetooth*PAN*'
})
if ($panAdapters.Count -eq 0) {
    throw '找不到 Windows Bluetooth PAN 網路介面。請先安裝藍牙驅動並完成手機配對。'
}

$adapter = $panAdapters |
    Sort-Object @{ Expression = { if ($_.Status -eq 'Up') { 0 } else { 1 } } }, ifIndex |
    Select-Object -First 1
$backupPath = Join-Path $PSScriptRoot 'Bluetooth-PAN-before-QuietPanel.json'

if (-not (Test-Path -LiteralPath $backupPath)) {
    $oldInterface = Get-NetIPInterface -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4
    $oldAddresses = @(Get-NetIPAddress -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4 |
        Select-Object IPAddress, PrefixLength, PrefixOrigin, SuffixOrigin)
    [ordered]@{
        savedAt = (Get-Date).ToString('o')
        interfaceName = $adapter.Name
        interfaceDescription = $adapter.InterfaceDescription
        interfaceGuid = $adapter.InterfaceGuid
        dhcp = [string]$oldInterface.Dhcp
        addresses = $oldAddresses
    } | ConvertTo-Json -Depth 4 | ForEach-Object {
        [IO.File]::WriteAllText($backupPath, $_, [Text.UTF8Encoding]::new($false))
    }
}

$currentAddresses = @(Get-NetIPAddress -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue)
foreach ($address in $currentAddresses) {
    Remove-NetIPAddress -InterfaceIndex $adapter.ifIndex -IPAddress $address.IPAddress -Confirm:$false
}

if ($RestoreDhcp) {
    Set-NetIPInterface -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4 -Dhcp Enabled
    Restart-NetAdapter -Name $adapter.Name -Confirm:$false
    Write-Output "已把 Bluetooth PAN（$($adapter.Name)）恢復為 DHCP。"
    Write-Output "原始狀態備份：$backupPath"
    exit 0
}

Set-NetIPInterface -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4 -Dhcp Disabled
New-NetIPAddress -InterfaceIndex $adapter.ifIndex -IPAddress $ComputerIp `
    -PrefixLength $PrefixLength -AddressFamily IPv4 | Out-Null

$settingsPath = Join-Path $PSScriptRoot 'QuietPanelBridge.json'
if (Test-Path -LiteralPath $settingsPath) {
    $settings = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
    if ($settings.PSObject.Properties.Name -contains 'phone_ip') {
        $settings.phone_ip = $PhoneIp
    } else {
        $settings | Add-Member -NotePropertyName phone_ip -NotePropertyValue $PhoneIp
    }
    $json = $settings | ConvertTo-Json -Depth 8
    [IO.File]::WriteAllText($settingsPath, $json, [Text.UTF8Encoding]::new($false))
}

Write-Output "Bluetooth PAN 已設定：電腦 $ComputerIp/$PrefixLength，手機 $PhoneIp。"
Write-Output "原始狀態備份：$backupPath"
Write-Output '現在可啟動 QuietPanelBridge.exe。'
