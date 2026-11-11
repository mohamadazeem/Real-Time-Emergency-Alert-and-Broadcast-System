param(
    [string]$EvidenceDirectory = 'runtime/evidence',
    [switch]$GuiFixture,
    [switch]$ReceiverWorkflow,
    [switch]$ReceiverGui,
    [switch]$Packaged
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Project-Paths.ps1')
$java = Get-ProjectJava
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$mode = if ($ReceiverGui) { 'receiver-gui' } elseif ($GuiFixture) { 'admin-gui' } elseif ($ReceiverWorkflow) { 'receiver-workflow' } else { 'integration' }
if ($Packaged) { $mode += '-packaged' }
$log = New-ProjectLog $mode
Push-Location (Get-ProjectRoot)
try {
    $evidence = Resolve-ProjectPath $EvidenceDirectory
    New-Item -ItemType Directory -Force -Path $evidence | Out-Null
    $classes = New-RuntimeDirectory ('evidence/harness-' + [guid]::NewGuid().ToString('N') + '/classes')
    if ($Packaged) {
        $runtime = (Resolve-ProjectPath 'runtime/dist/lib') + '\*'
        if (!(Test-Path -LiteralPath (Resolve-ProjectPath 'runtime/dist/lib/server-0.1.0-SNAPSHOT.jar'))) {
            throw 'Run scripts/Prepare-Demo.ps1 first.'
        }
    } else {
        # Maven's actual resolved dependency paths respect the configured normal Maven cache.
        $reports = @('common','server') | ForEach-Object {
            Get-ChildItem "$_/target/surefire-reports/TEST-*.xml" | Select-Object -First 1
        }
        $dependencies = foreach ($report in $reports) {
            [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
            $classpath = ($xml.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
            $classpath.Split(';') | Where-Object { $_ -match '\\(gson|sqlite-jdbc|error_prone_annotations)\\' -and $_.EndsWith('.jar') }
        }
        $dependencies = @($dependencies | Sort-Object -Unique)
        if ($dependencies.Count -lt 2) { throw 'Run scripts/Build.ps1 first; Maven runtime dependency evidence is missing.' }
        $moduleClasses = @('common','server','admin','client') | ForEach-Object { (Resolve-Path "$_/target/classes").Path }
        $runtime = (@($moduleClasses) + $dependencies) -join ';'
    }
    $cp = $runtime + ';' + $classes
    Write-Output "Verification log: $log"
    $result = Invoke-ProjectNative -Executable $javac -Arguments @('--release','17','-encoding','UTF-8','-cp',$cp,'-d',$classes,'scripts/IntegrationHarness.java','client/scripts/ReceiverWorkflowHarness.java') -Log $log
    if ($result -ne 0) { throw "Harness compilation failed. See $log" }
    $entry = if ($ReceiverGui) { 'ReceiverWorkflowHarness$GuiFixture' } elseif ($ReceiverWorkflow) {
        'ReceiverWorkflowHarness'
    } elseif ($GuiFixture) { 'IntegrationHarness$GuiFixture' } else { 'IntegrationHarness' }
    $result = Invoke-ProjectNative -Executable $java -Arguments @('-cp',$cp,$entry,$evidence) -Log $log -Append
    Get-Content -LiteralPath $log -Tail 18
    if ($result -ne 0) { throw "Real-application verification failed (exit $result). See $log" }
} finally { Pop-Location }
