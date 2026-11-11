$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Project-Paths.ps1')
Push-Location (Get-ProjectRoot)
try {
    $artifacts = @('common','server','admin','client') | ForEach-Object {
        $jar = "$_\target\$_-0.1.0-SNAPSHOT.jar"
        if (!(Test-Path -LiteralPath $jar)) { throw "Missing $jar; run mvn -B clean verify first." }
        (Resolve-Path -LiteralPath $jar).Path
    }
    # These are the production dependencies declared by the reviewed POMs.
    # Read the resolved paths to respect custom Maven cache locations.
    $report = Get-ChildItem 'server\target\surefire-reports\TEST-*.xml' | Select-Object -First 1
    if (!$report) { throw 'Run mvn -B clean verify first (Surefire report required).' }
    [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
    $classpath = ($xml.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
    $runtimeJars = @($classpath.Split(';') | Where-Object {
        $_ -match '\\(gson|sqlite-jdbc|error_prone_annotations)\\' -and $_.EndsWith('.jar')
    } | Sort-Object -Unique)
    if ($runtimeJars.Count -lt 2) { throw 'Runtime dependency resolution evidence missing.' }
    $distribution = New-RuntimeDirectory 'dist'
    $staging = Join-Path $distribution ('prepare-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $staging | Out-Null
    foreach ($jar in (@($artifacts) + $runtimeJars)) { Copy-Item -LiteralPath $jar -Destination $staging }
    $destination = Join-Path $distribution 'lib'
    if (Test-Path -LiteralPath $destination) {
        $running = @(Get-CimInstance Win32_Process | Where-Object {
            $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match 'edu\.ec5205\.alert|IntegrationHarness|ReceiverWorkflowHarness|Real-Time-Emergency-Alert-and-Broadcast-System'
        })
        if ($running.Count) { throw 'Close project applications before replacing the prepared distribution. Staging files retained safely.' }
        $archive = New-RuntimeDirectory ('dist/archive/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N'))
        $runtimePrefix = (Resolve-ProjectPath 'runtime') + [System.IO.Path]::DirectorySeparatorChar
        foreach ($path in @($destination, $archive, $staging)) {
            if (![System.IO.Path]::GetFullPath($path).StartsWith($runtimePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
                throw 'Distribution move escaped the intended runtime directory.'
            }
        }
        Move-Item -LiteralPath $destination -Destination (Join-Path $archive 'lib')
        Write-Output "Previous distribution preserved: $archive"
    }
    Move-Item -LiteralPath $staging -Destination $destination
    Write-Output "Prepared $($artifacts.Count + $runtimeJars.Count) jars in runtime\dist\lib."
} finally { Pop-Location }
