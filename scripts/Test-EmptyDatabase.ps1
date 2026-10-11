[CmdletBinding()]
param([int]$BackendPort = 18080, [int]$FrontendPort = 15173)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot
$workRoot = Join-Path $projectRoot '.work'
$settings = Get-Content (Join-Path $workRoot 'dev-environment.json') -Raw | ConvertFrom-Json
$mysqlClient = Join-Path $settings.mysqlBin 'mysql.exe'
$clientOptions = Join-Path $workRoot 'mysql-client.cnf'
function Query([string]$sql) {
    $value = & $mysqlClient "--defaults-file=$clientOptions" --batch --skip-column-names -e $sql
    if ($LASTEXITCODE -ne 0) { throw 'Database verification query failed.' }
    return ($value -join "`n").Trim()
}
$actualDirectory = [IO.Path]::GetFullPath((Query 'SELECT @@datadir')).TrimEnd('\','/')
$expectedDirectory = [IO.Path]::GetFullPath((Join-Path $workRoot 'mysql-dev')).TrimEnd('\','/')
if ($actualDirectory -ne $expectedDirectory) { throw 'This is not the project isolated database; verification refused.' }
if ((Query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='ai_teaching'") -ne '0') {
    throw 'Database is not empty. This acceptance script never drops existing data; run it before the first migration.'
}
Write-Output 'PASS: MySQL database contains zero tables before startup.'
foreach ($port in @($BackendPort, $FrontendPort)) {
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $probe.Start() } finally { $probe.Stop() }
}
$jar = Join-Path $projectRoot 'backend/target/ai-teaching-backend-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path $jar)) { throw 'Build the backend package first.' }
. (Join-Path $PSScriptRoot 'Use-DevEnvironment.ps1')
$env:SERVER_PORT = [string]$BackendPort
$env:DEV_API_TARGET = "http://127.0.0.1:$BackendPort"
Push-Location $projectRoot
$backendProcess = $null
$frontendProcess = $null
try {
    foreach ($round in @('first', 'repeat')) {
        & java -jar $jar --spring.profiles.active=migrate *> (Join-Path $workRoot "migration-$round.log")
        if ($LASTEXITCODE -ne 0) { throw "Migration failed: .work/migration-$round.log" }
    }
    if ((Query 'SELECT COUNT(*) FROM ai_teaching.flyway_schema_history WHERE version=1 AND success=1') -ne '1') { throw 'V1 migration history mismatch.' }
    if ((Query 'SELECT COUNT(*) FROM ai_teaching.flyway_schema_history WHERE version=2 AND success=1') -ne '1') { throw 'V2 migration history mismatch.' }
    if ((Query "SELECT COUNT(*) FROM ai_teaching.flyway_schema_history WHERE version=20 AND success=1") -ne '1') { throw 'V20 migration history mismatch.' }
    if ((Query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='ai_teaching'") -ne '33') { throw 'Expected 32 business tables and Flyway history.' }
    Write-Output 'PASS: V1-V20 applied exactly once; 32 business tables and history exist after repeated migration.'
    $backendProcess = Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-jar', "`"$jar`"") -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $workRoot 'backend-smoke.log') -RedirectStandardError (Join-Path $workRoot 'backend-smoke-error.log')
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $backendProcess.Refresh()
        if ($backendProcess.HasExited) { throw 'Backend exited; inspect .work/backend-smoke.log.' }
        try {
            $statusResponse = Invoke-WebRequest "http://127.0.0.1:$BackendPort/api/v1/system/status" -TimeoutSec 2
            if (($statusResponse.Content | ConvertFrom-Json).status -eq 'UP') { break }
        } catch { if ($attempt -eq 59) { throw } }
        Start-Sleep -Seconds 1
    }
    if (-not $statusResponse -or ($statusResponse.Content | ConvertFrom-Json).status -ne 'UP') { throw 'Status endpoint did not become ready.' }
    $viteCli = Join-Path $projectRoot 'frontend/node_modules/vite/bin/vite.js'
    $frontendProcess = Start-Process -FilePath (Get-Command node).Source -ArgumentList @("`"$viteCli`"", '--host', '127.0.0.1', '--port', "$FrontendPort", '--strictPort') -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $workRoot 'frontend-smoke.log') -RedirectStandardError (Join-Path $workRoot 'frontend-smoke-error.log')
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $frontendProcess.Refresh()
        if ($frontendProcess.HasExited) { throw 'Vite exited; inspect .work/frontend-smoke-error.log.' }
        try {
            $response = Invoke-WebRequest "http://127.0.0.1:$FrontendPort/api/v1/system/status" -Headers @{ 'X-Request-ID' = 'dev-smoke' } -TimeoutSec 2
            if (($response.Content | ConvertFrom-Json).status -eq 'UP') { break }
        } catch { if ($attempt -eq 29) { throw } }
        Start-Sleep -Seconds 1
    }
    if (-not $response -or ($response.Content | ConvertFrom-Json).status -ne 'UP' -or $response.Headers['X-Request-ID'] -ne 'dev-smoke') { throw 'Frontend proxy did not return verified backend status/request ID.' }
    Write-Output 'PASS: frontend proxy reaches backend UP and preserves X-Request-ID.'
    try {
        Invoke-WebRequest "http://127.0.0.1:$FrontendPort/api/v1/courses" -TimeoutSec 2 | Out-Null
        throw 'Protected route unexpectedly returned success.'
    } catch {
        if ([int]$_.Exception.Response.StatusCode -ne 401) { throw }
    }
    Write-Output 'PASS: business route remains unauthorized (401) through frontend proxy.'
} finally {
    foreach ($childProcess in @($frontendProcess, $backendProcess)) {
        if ($null -ne $childProcess) {
            $childProcess.Refresh()
            if (-not $childProcess.HasExited) { Stop-Process -Id $childProcess.Id; $childProcess.WaitForExit() }
        }
    }
    Pop-Location
}
