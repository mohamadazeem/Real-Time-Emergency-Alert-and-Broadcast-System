$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Project-Paths.ps1')
$null = Get-ProjectJava
$log = New-ProjectLog 'build'
Push-Location (Get-ProjectRoot)
try {
    Write-Output "Maven log: $log"
    $result = Invoke-ProjectNative -Executable 'mvn' -Arguments @('-B','clean','verify') -Log $log
    Get-Content -LiteralPath $log -Tail 18
    if ($result -ne 0) { throw "Maven verification failed (exit $result). See $log" }
} finally { Pop-Location }
