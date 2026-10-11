[CmdletBinding()]
param([string]$MySqlBin = (Split-Path (Get-Command mysqld -ErrorAction Stop).Source), [int]$Port = 13306)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot
$workRoot = Join-Path $projectRoot '.work'
$dataRoot = Join-Path $workRoot 'mysql-dev'
$configPath = Join-Path $workRoot 'dev-environment.json'
$mysqlServer = Join-Path $MySqlBin 'mysqld.exe'
$mysqlClient = Join-Path $MySqlBin 'mysql.exe'
if ((& $mysqlServer --no-defaults --version) -notmatch 'Ver 8\.0\.44\b') { throw 'This reproducible development script requires MySQL 8.0.44; do not silently substitute versions.' }
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Port must be between 1024 and 65535.' }
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
try { $listener.Start() } catch { throw "Port $Port is occupied. No existing server was changed." } finally { $listener.Stop() }
New-Item -ItemType Directory -Force $workRoot | Out-Null
$firstStart = -not (Test-Path $configPath)
if ($firstStart) {
    if (Test-Path $dataRoot) { throw 'Data directory exists without its environment file. Preserve it and inspect initialization logs; no automatic deletion is performed.' }
    function New-LocalPassword {
        $bytes = [byte[]]::new(32)
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
    }
    $settings = [ordered]@{
        port = $Port; mysqlBin = $MySqlBin; rootPassword = (New-LocalPassword)
        DB_USERNAME = 'ai_teaching'; DB_PASSWORD = (New-LocalPassword)
        DB_URL = "jdbc:mysql://127.0.0.1:$Port/ai_teaching?sslMode=DISABLED&allowPublicKeyRetrieval=true"
        SERVER_PORT = '8080'; FILE_STORAGE_DIR = (Join-Path $workRoot 'files')
        DEV_API_TARGET = 'http://127.0.0.1:8080'; VITE_API_BASE_URL = '/api/v1'
    }
    & $mysqlServer --no-defaults --initialize-insecure "--basedir=$(Split-Path $MySqlBin)" "--datadir=$dataRoot" "--log-error=$(Join-Path $workRoot 'mysql-init.log')"
    if ($LASTEXITCODE -ne 0) { throw 'MySQL initialization failed. See .work/mysql-init.log; no existing database was touched.' }
    $settings | ConvertTo-Json | Set-Content $configPath -Encoding utf8
} else {
    $settings = Get-Content $configPath -Raw | ConvertFrom-Json
    if ($settings.port -ne $Port -or $settings.mysqlBin -ne $MySqlBin) { throw 'Existing local configuration uses another port or MySQL directory.' }
}
$utf8 = [Text.UTF8Encoding]::new($false)
$clientOptions = Join-Path $workRoot 'mysql-client.cnf'
[IO.File]::WriteAllText($clientOptions, "[client]`nuser=root`npassword=$($settings.rootPassword)`nhost=127.0.0.1`nport=$Port`nprotocol=tcp`n", $utf8)
$initSql = Join-Path $workRoot 'mysql-bootstrap.sql'
$provisionedMarker = Join-Path $workRoot 'mysql-dev-provisioned'
# Only first start provisions the database/account. All application schema changes belong to Flyway.
$arguments = @('--no-defaults', "--basedir=`"$(Split-Path $MySqlBin)`"", "--datadir=`"$dataRoot`"", "--port=$Port", '--bind-address=127.0.0.1', '--mysqlx=0', "--log-error=`"$(Join-Path $workRoot 'mysql-dev.log')`"")
if (-not (Test-Path $provisionedMarker)) {
    $sql = "ALTER USER 'root'@'localhost' IDENTIFIED BY '$($settings.rootPassword)';`nCREATE DATABASE IF NOT EXISTS ai_teaching CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`nCREATE USER IF NOT EXISTS 'ai_teaching'@'127.0.0.1' IDENTIFIED BY '$($settings.DB_PASSWORD)';`nGRANT ALL PRIVILEGES ON ai_teaching.* TO 'ai_teaching'@'127.0.0.1';`n"
    [IO.File]::WriteAllText($initSql, $sql, $utf8)
}
if (Test-Path $initSql) {
    $arguments += "--init-file=`"$initSql`""
}
$server = Start-Process -FilePath $mysqlServer -ArgumentList $arguments -WindowStyle Hidden -PassThru
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    $server.Refresh()
    if ($server.HasExited) { throw 'Isolated MySQL exited; inspect .work/mysql-dev.log.' }
    $ready = $false
    try {
        & $mysqlClient "--defaults-file=$clientOptions" --connect-timeout=1 --batch --skip-column-names -e 'SELECT 1' 2>$null | Out-Null
        $ready = $LASTEXITCODE -eq 0
    } catch {
        # PowerShell 5 can promote native stderr to NativeCommandError while MySQL is still starting.
        $ready = $false
    }
    if ($ready) {
        if (Test-Path $initSql) { Remove-Item -LiteralPath $initSql }
        Set-Content -LiteralPath $provisionedMarker -Value 'ready' -Encoding ascii
        New-Item -ItemType Directory -Force $settings.FILE_STORAGE_DIR | Out-Null
        Write-Output "Isolated MySQL 8.0.44 ready on 127.0.0.1:$Port; configuration: .work/dev-environment.json (private)."
        exit 0
    }
    Start-Sleep -Seconds 1
}
throw 'MySQL readiness timed out; inspect .work/mysql-dev.log. The script does not delete data.'
