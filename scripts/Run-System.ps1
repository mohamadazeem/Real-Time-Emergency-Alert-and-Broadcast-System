param(
    [ValidateRange(1,65535)][int]$Port = 5050,
    [string]$ServerHost = '127.0.0.1',
    [string]$AdminUsername = 'classroom-admin',
    [string]$DatabasePath = 'runtime/data/classroom-demo.db',
    [switch]$SkipBuild,
    [switch]$SkipPrepare,
    [switch]$NoReceiverB
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Project-Paths.ps1')

$password = Read-Host 'Disposable classroom password' -AsSecureString
$plainPassword = [System.Net.NetworkCredential]::new('', $password).Password
if ([string]::IsNullOrWhiteSpace($plainPassword)) {
    throw 'A non-empty classroom password is required.'
}

$previousUsername = $env:EMERGENCY_ADMIN_USERNAME
$previousPassword = $env:EMERGENCY_ADMIN_PASSWORD

try {
    if (!$SkipBuild) {
        & (Join-Path $PSScriptRoot 'Build.ps1')
    }
    if (!$SkipPrepare) {
        & (Join-Path $PSScriptRoot 'Prepare-Demo.ps1')
    }

    $env:EMERGENCY_ADMIN_USERNAME = $AdminUsername
    $env:EMERGENCY_ADMIN_PASSWORD = $plainPassword
    $env:EMERGENCY_PORT = $Port.ToString()

    $root = Get-ProjectRoot
    $startApp = Join-Path $PSScriptRoot 'Start-App.ps1'

    $serverCommand = "Set-Location -LiteralPath '$($root.Replace("'", "''"))'; & '$($startApp.Replace("'", "''"))' -Application Server -Port $Port -DatabasePath '$($DatabasePath.Replace("'", "''"))'"
    $adminCommand = "Set-Location -LiteralPath '$($root.Replace("'", "''"))'; & '$($startApp.Replace("'", "''"))' -Application Admin"
    $receiverACommand = "Set-Location -LiteralPath '$($root.Replace("'", "''"))'; & '$($startApp.Replace("'", "''"))' -Application Receiver -ServerHost '$($ServerHost.Replace("'", "''"))' -Port $Port -Name 'Receiver A' -IdentityFile 'runtime/data/receiver-a.json'"
    $receiverBCommand = "Set-Location -LiteralPath '$($root.Replace("'", "''"))'; & '$($startApp.Replace("'", "''"))' -Application Receiver -ServerHost '$($ServerHost.Replace("'", "''"))' -Port $Port -Name 'Receiver B' -IdentityFile 'runtime/data/receiver-b.json'"

    $powershell = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    Start-Process -FilePath $powershell -ArgumentList @('-NoProfile', '-NoExit', '-ExecutionPolicy', 'Bypass', '-Command', $serverCommand)
    Start-Process -FilePath $powershell -ArgumentList @('-NoProfile', '-NoExit', '-ExecutionPolicy', 'Bypass', '-Command', $adminCommand)
    Start-Process -FilePath $powershell -ArgumentList @('-NoProfile', '-NoExit', '-ExecutionPolicy', 'Bypass', '-Command', $receiverACommand)
    if (!$NoReceiverB) {
        Start-Process -FilePath $powershell -ArgumentList @('-NoProfile', '-NoExit', '-ExecutionPolicy', 'Bypass', '-Command', $receiverBCommand)
    }

    Write-Output "Started server, admin, and receiver A at port $Port."
    if (!$NoReceiverB) { Write-Output 'Started receiver B.' }
} finally {
    $env:EMERGENCY_ADMIN_USERNAME = $previousUsername
    $env:EMERGENCY_ADMIN_PASSWORD = $previousPassword
    Remove-Variable plainPassword -ErrorAction SilentlyContinue
    Remove-Variable password -ErrorAction SilentlyContinue
}
