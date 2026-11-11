param(
    [Parameter(Mandatory = $true)][ValidateSet('Server','Admin','Receiver')][string]$Application,
    [string]$ServerHost = '127.0.0.1',
    [ValidateRange(1,65535)][int]$Port = 5050,
    [string]$Name = 'Receiver 01',
    [string]$IdentityFile = 'runtime/data/client-identity.json',
    [string]$DatabasePath,
    [switch]$Console,
    [switch]$Check
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Project-Paths.ps1')
$java = Get-ProjectJava
$classpath = (Resolve-ProjectPath 'runtime/dist/lib') + '\*'
if (!(Test-Path -LiteralPath (Resolve-ProjectPath 'runtime/dist/lib/client-0.1.0-SNAPSHOT.jar'))) {
    throw 'Run scripts/Build.ps1 and scripts/Prepare-Demo.ps1 first.'
}
$arguments = @()
$previousDatabase = $env:EMERGENCY_DB_PATH
$previousPort = $env:EMERGENCY_PORT
Push-Location (Get-ProjectRoot)
try {
    switch ($Application) {
        'Server' {
            $mainClass = 'edu.ec5205.alert.server.ServerMain'
            if ($PSBoundParameters.ContainsKey('DatabasePath')) {
                $env:EMERGENCY_DB_PATH = Resolve-ConfiguredRuntimePath $DatabasePath
            } elseif (![string]::IsNullOrWhiteSpace($env:EMERGENCY_DB_PATH)) {
                $env:EMERGENCY_DB_PATH = Resolve-ConfiguredRuntimePath $env:EMERGENCY_DB_PATH
            }
            # An explicit environment port still wins unless -Port was supplied.
            if ($PSBoundParameters.ContainsKey('Port')) { $env:EMERGENCY_PORT = $Port.ToString() }
        }
        'Admin' { $mainClass = 'edu.ec5205.alert.admin.AdminMain' }
        'Receiver' {
            $mainClass = 'edu.ec5205.alert.client.ClientMain'
            $identity = Resolve-ConfiguredRuntimePath $IdentityFile
            $arguments = @('--host', $ServerHost, '--port', $Port.ToString(), '--name', $Name, '--id-file', $identity)
        }
    }
    if ($Console) { $arguments += '--console' }
    if ($Check) {
        if (!(Test-Path -LiteralPath (Resolve-ProjectPath ('runtime/dist/lib/' + $(switch ($Application) {
            'Server' { 'server' }; 'Admin' { 'admin' }; 'Receiver' { 'client' }
        }) + '-0.1.0-SNAPSHOT.jar')))) { throw 'Application jar missing.' }
        Write-Output "Validated $Application entry $mainClass; working directory: $(Get-ProjectRoot); classpath: $classpath"
        return
    }
    if ($Console -and $Application -ne 'Server') {
        # Keep password prompts interactive; do not capture typed admin credentials.
        $result = Invoke-ProjectNative -Executable $java -Arguments (@('-cp',$classpath,$mainClass) + $arguments)
    } else {
        $log = New-ProjectLog $Application.ToLowerInvariant()
        Write-Output "$Application log: $log"
        $result = Invoke-ProjectNative -Executable $java -Arguments (@('-cp',$classpath,$mainClass) + $arguments) -Log $log
    }
    if ($result -ne 0) { throw "$Application exited with code $result." }
} finally {
    $env:EMERGENCY_DB_PATH = $previousDatabase
    $env:EMERGENCY_PORT = $previousPort
    Pop-Location
}
