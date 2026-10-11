$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot
$settings = Get-Content (Join-Path $projectRoot '.work/dev-environment.json') -Raw | ConvertFrom-Json
$port = [int]$settings.port
$clientOptions = Join-Path $projectRoot '.work/mysql-client.cnf'
$mysqlClient = Join-Path $settings.mysqlBin 'mysql.exe'
$actualDirectory = & $mysqlClient "--defaults-file=$clientOptions" --batch --skip-column-names -e 'SELECT @@datadir'
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify isolated server; shutdown refused.' }
$expected = [IO.Path]::GetFullPath((Join-Path $projectRoot '.work/mysql-dev')).TrimEnd('\','/')
$actual = [IO.Path]::GetFullPath($actualDirectory.Trim()).TrimEnd('\','/')
if ($actual -ne $expected) { throw 'Connected server is not the project development instance; shutdown refused.' }
$pidFile = & $mysqlClient "--defaults-file=$clientOptions" --batch --skip-column-names -e 'SELECT @@pid_file'
if ($LASTEXITCODE -ne 0) { throw 'Cannot determine server PID; shutdown refused.' }
$pidPath = [IO.Path]::GetFullPath($pidFile.Trim())
if (-not $pidPath.StartsWith($expected + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'PID file is outside project data directory; shutdown refused.' }
& (Join-Path $settings.mysqlBin 'mysqladmin.exe') "--defaults-file=$clientOptions" shutdown
if ($LASTEXITCODE -ne 0) { throw 'MySQL shutdown failed.' }
$stopped = $false
foreach ($attempt in 1..60) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        if (-not $client.ConnectAsync('127.0.0.1', $port).Wait(500)) {
            $stopped = $true
            break
        }
    } catch {
        $stopped = $true
        break
    } finally {
        $client.Dispose()
    }
    Start-Sleep -Milliseconds 500
}
if (-not $stopped) { throw 'Shutdown still running; wait for it to finish before restarting.' }
Write-Output 'Project MySQL stopped; data preserved.'
