# Shared by entry scripts; relative script arguments are always repository-root relative.
$script:EmergencyProjectRoot = Split-Path -Parent $PSScriptRoot
function Get-ProjectRoot { return $script:EmergencyProjectRoot }
function Resolve-ProjectPath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'A non-blank path is required.' }
    if ([System.IO.Path]::IsPathRooted($Path)) { return [System.IO.Path]::GetFullPath($Path) }
    return [System.IO.Path]::GetFullPath((Join-Path (Get-ProjectRoot) $Path))
}
function New-RuntimeDirectory([string]$RelativePath) {
    $directory = Resolve-ProjectPath ('runtime/' + $RelativePath)
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    return $directory
}
function New-ProjectLog([string]$Name) {
    $directory = New-RuntimeDirectory 'logs'
    return Join-Path $directory ($Name + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N') + '.log')
}
function Get-ProjectJava {
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to your configured Temurin JDK 17.' }
    $release = Join-Path $env:JAVA_HOME 'release'
    if (!(Test-Path -LiteralPath $release) -or (Get-Content -LiteralPath $release -Raw) -notmatch 'JAVA_VERSION="17[."]') {
        throw 'JAVA_HOME must identify JDK 17; no alternate runtime will be substituted.'
    }
    return Join-Path $env:JAVA_HOME 'bin/java.exe'
}
function Invoke-ProjectNative([string]$Executable, [string[]]$Arguments, [string]$Log, [switch]$Append) {
    $null = Get-Command $Executable -ErrorAction Stop
    $preference = $ErrorActionPreference
    try {
        # Windows PowerShell otherwise turns expected stderr (including test diagnostics)
        # into terminating NativeCommandError records before the real exit code is checked.
        $ErrorActionPreference = 'Continue'
        if ($Log) {
            if ($Append) { & $Executable @Arguments *>> $Log }
            else { & $Executable @Arguments *> $Log }
        } else { & $Executable @Arguments | Out-Host }
        $result = $LASTEXITCODE
    } finally { $ErrorActionPreference = $preference }
    return $result
}
function Resolve-ConfiguredRuntimePath([string]$Path) {
    $resolved = Resolve-ProjectPath $Path
    # Do not silently regenerate an identity/database when an old command points at a moved file.
    $rootPrefix = (Get-ProjectRoot).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (!$resolved.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) { return $resolved }
    $relative = $resolved.Substring($rootPrefix.Length).Replace('\', '/')
    $replacement = $null
    if ($relative.StartsWith('data/')) { $replacement = 'runtime/' + $relative }
    elseif ($relative -eq 'client-identity.json') { $replacement = 'runtime/data/client-identity.json' }
    if ($replacement -and !(Test-Path -LiteralPath $resolved) -and (Test-Path -LiteralPath (Resolve-ProjectPath $replacement))) {
        throw "Configured path '$Path' was moved to '$replacement'. Update your command/environment explicitly; no new identity/database was created."
    }
    return $resolved
}
