# One-time, collision-safe relocation. Does not touch source, module targets, or Git state.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Project-Paths.ps1')
$root = Get-ProjectRoot
$prefix = $root.TrimEnd('\','/') + [System.IO.Path]::DirectorySeparatorChar
$running = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match 'edu\.ec5205\.alert|IntegrationHarness|ReceiverWorkflowHarness|Real-Time-Emergency-Alert-and-Broadcast-System'
})
if ($running.Count) { throw 'Project applications are running. Close them before relocating runtime files.' }
$mapping = [System.Collections.Generic.List[object]]::new()
function Add-MigrationFile($File, [string]$RelativeDestination) {
    $destination = Resolve-ProjectPath $RelativeDestination
    if (!$File.FullName.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase) -or
        !$destination.StartsWith(($prefix + 'runtime' + [System.IO.Path]::DirectorySeparatorChar), [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Migration path escaped the intended repository/runtime directory.'
    }
    if (Test-Path -LiteralPath $destination) { throw "Destination collision; nothing overwritten: $RelativeDestination" }
    $uuid = $null
    if ($File.Extension -eq '.json') {
        try { $uuid = (Get-Content -LiteralPath $File.FullName -Raw | ConvertFrom-Json).clientId } catch { }
    }
    $mapping.Add([pscustomobject]@{
        Source = $File.FullName; Destination = $destination; Length = $File.Length
        Sha256 = (Get-FileHash -LiteralPath $File.FullName -Algorithm SHA256).Hash; ClientId = $uuid
    })
}
foreach ($legacy in @('data','demo-dist','integration-evidence')) {
    $directory = Resolve-ProjectPath $legacy
    if (!(Test-Path -LiteralPath $directory)) { continue }
    if (@(Get-ChildItem -LiteralPath $directory -Recurse -Force | Where-Object { $_.Attributes -band [System.IO.FileAttributes]::ReparsePoint }).Count) {
        throw "Refusing reparse points inside $legacy; inspect them manually."
    }
    foreach ($file in Get-ChildItem -LiteralPath $directory -Recurse -File -Force) {
        $suffix = $file.FullName.Substring($directory.Length + 1)
        $destination = switch ($legacy) {
            'data' { 'runtime/data/' + $suffix }
            'demo-dist' { 'runtime/dist/' + $suffix }
            'integration-evidence' {
                if ($file.Extension -eq '.log') { 'runtime/logs/evidence/' + $suffix }
                else { 'runtime/evidence/' + $suffix }
            }
        }
        Add-MigrationFile $file $destination
    }
}
foreach ($file in Get-ChildItem -LiteralPath $root -File -Filter '*.log') { Add-MigrationFile $file ('runtime/logs/' + $file.Name) }
foreach ($file in Get-ChildItem -LiteralPath $root -File | Where-Object {
    $_.Name -match '\.(db|sqlite|sqlite3|db3)(-wal|-shm|-journal)?$|^(client-identity|receiver.*-identity)\.json$'
}) { Add-MigrationFile $file ('runtime/data/' + $file.Name) }
if (!$mapping.Count) { Write-Output 'No legacy runtime files found.'; return }
if (@($mapping | Group-Object Destination | Where-Object Count -gt 1).Count) { throw 'Two files map to the same destination; no files moved.' }
$audit = New-RuntimeDirectory ('evidence/migration-' + [guid]::NewGuid().ToString('N'))
$mapping | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $audit 'manifest.json') -Encoding UTF8
$retainedDatabases = @($mapping | Where-Object { $_.Source.StartsWith((Resolve-ProjectPath 'data') + '\') -and $_.Source -match '\.(db|sqlite|sqlite3|db3)$' })
$java = Get-ProjectJava
function Inspect-Copy([string]$Database, [string]$Phase, [string]$Classpath) {
    $copyDirectory = Join-Path $audit ($Phase + '/' + [System.IO.Path]::GetFileName($Database))
    New-Item -ItemType Directory -Force -Path $copyDirectory | Out-Null
    foreach ($suffix in @('', '-wal', '-shm', '-journal')) {
        if (Test-Path -LiteralPath ($Database + $suffix)) { Copy-Item -LiteralPath ($Database + $suffix) -Destination $copyDirectory }
    }
    $copy = Join-Path $copyDirectory ([System.IO.Path]::GetFileName($Database))
    $result = & $java --class-path $Classpath (Join-Path $PSScriptRoot 'Inspect-Database.java') $copy
    if ($LASTEXITCODE -ne 0) { throw 'Read-only database snapshot inspection failed; inspect migration audit.' }
    return @($result)
}
$before = @{}
$oldClasspath = (Resolve-ProjectPath 'demo-dist/lib') + '\*'
foreach ($database in $retainedDatabases) {
    $before[$database.Source] = Inspect-Copy $database.Source 'before' $oldClasspath
}
$before | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $audit 'database-records-before.json') -Encoding UTF8
# Recheck immediately before the first move, in case an application was started during inspection.
if (@(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match 'edu\.ec5205\.alert|IntegrationHarness|ReceiverWorkflowHarness|Real-Time-Emergency-Alert-and-Broadcast-System'
}).Count) { throw 'A project application started during inspection. Close it before retrying.' }
foreach ($entry in $mapping) {
    if (Test-Path -LiteralPath $entry.Destination) { throw 'Destination appeared during migration; stopped without overwriting it.' }
    New-Item -ItemType Directory -Force -Path (Split-Path $entry.Destination -Parent) | Out-Null
    Move-Item -LiteralPath $entry.Source -Destination $entry.Destination
}
foreach ($entry in $mapping) {
    if ((Get-FileHash -LiteralPath $entry.Destination -Algorithm SHA256).Hash -ne $entry.Sha256) { throw 'Moved file hash differs; preserve the migration manifest for recovery.' }
    if ($entry.ClientId) {
        if ((Get-Content -LiteralPath $entry.Destination -Raw | ConvertFrom-Json).clientId -cne $entry.ClientId) { throw 'Retained identity changed.' }
    }
}
$after = @{}
foreach ($database in $retainedDatabases) {
    $after[$database.Source] = Inspect-Copy $database.Destination 'after' ((Resolve-ProjectPath 'runtime/dist/lib') + '\*')
    if (@(Compare-Object $before[$database.Source] $after[$database.Source]).Count) { throw 'Database logical records differ after migration.' }
}
$after | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $audit 'database-records-after.json') -Encoding UTF8
foreach ($legacy in @('data','demo-dist','integration-evidence')) {
    $directory = Resolve-ProjectPath $legacy
    if (!(Test-Path -LiteralPath $directory)) { continue }
    # Only remove verified empty directories, deepest first. Never recursively delete data.
    $directories = @(Get-ChildItem -LiteralPath $directory -Directory -Recurse | Sort-Object { $_.FullName.Length } -Descending)
    foreach ($empty in @($directories.FullName) + @($directory)) {
        if ($empty -and $empty.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase) -and
            @(Get-ChildItem -LiteralPath $empty -Force).Count -eq 0) { Remove-Item -LiteralPath $empty }
    }
}
Write-Output "Moved $($mapping.Count) files without collisions; every SHA-256 and retained UUID matched."
Write-Output "Verified logical records in $($retainedDatabases.Count) retained database(s) using disposable copies. Audit: $audit"
foreach ($records in $after.Values) { $records }
