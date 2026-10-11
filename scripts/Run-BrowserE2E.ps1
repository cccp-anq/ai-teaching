# Sprint 1 browser E2E orchestration (Round Phase D).
# Starts the isolated MySQL, a fresh dedicated database (ai_teaching_e2e ONLY - the
# script refuses any other name), the backend jar with the demo profile, and the Vite
# dev server, then runs Playwright and tears everything down. Nothing is pushed or
# committed here; the demo password is generated at runtime and never printed.

[CmdletBinding()]
param(
    [int]$BackendPort = 18080,
    [int]$FrontendPort = 5173,
    [switch]$SkipBuild,
    [string]$ClassroomMediaFixture,
    [string]$FfprobePath
)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$projectRoot = (Get-Location).Path
$work = Join-Path $projectRoot '.work'
New-Item -ItemType Directory -Force -Path $work | Out-Null

if ([bool]$ClassroomMediaFixture -ne [bool]$FfprobePath) {
    throw 'ClassroomMediaFixture and FfprobePath must be supplied together.'
}
$classroomFixturePath = $null
$classroomFfprobePath = $null
$classroomStorage = $null
$previousClassroomEnv = @{}
foreach ($key in @('CLASSROOM_FFPROBE_PATH', 'CLASSROOM_MOCK_MODULES_ENABLED',
        'FILE_STORAGE_DIR', 'E2E_CLASSROOM_MEDIA_FIXTURE')) {
    $previousClassroomEnv[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
if ($ClassroomMediaFixture) {
    $classroomFixturePath = (Resolve-Path -LiteralPath $ClassroomMediaFixture -ErrorAction Stop).Path
    $classroomFfprobePath = (Resolve-Path -LiteralPath $FfprobePath -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $classroomFixturePath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $classroomFfprobePath -PathType Leaf)) {
        throw 'Classroom fixture and ffprobe must both be existing files.'
    }
    $classroomStorage = Join-Path $work ('browser-classroom-' + [Guid]::NewGuid().ToString('N'))
}

function Test-PortListening([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $connected = $client.ConnectAsync('127.0.0.1', $Port).Wait(500)
        return $connected -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

# --- 1. isolated MySQL ----------------------------------------------------------
if (-not (Test-PortListening 13306)) {
    & (Join-Path $PSScriptRoot 'Start-DevDatabase.ps1')
} else {
    Write-Host 'MySQL 13306 already listening; reusing.'
}

# --- 2. dedicated fresh e2e database (name is a hard constant) -------------------
$env = Get-Content (Join-Path $work 'dev-environment.json') -Raw | ConvertFrom-Json
$e2eDatabase = 'ai_teaching_e2e'
$mysqlBin = if ($env.mysqlBin) { $env.mysqlBin } else { 'mysql' }
$clientOptions = Join-Path $work 'mysql-client.cnf'
$actualDirectory = & (Join-Path $mysqlBin 'mysql.exe') "--defaults-file=$clientOptions" --batch --skip-column-names -e 'SELECT @@datadir'
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify MySQL 13306; E2E reset refused.' }
$expectedDirectory = [IO.Path]::GetFullPath((Join-Path $work 'mysql-dev')).TrimEnd('\','/')
$actualDirectory = [IO.Path]::GetFullPath($actualDirectory.Trim()).TrimEnd('\','/')
if ($actualDirectory -ne $expectedDirectory) { throw 'MySQL 13306 is not the project-isolated instance; E2E reset refused.' }
& (Join-Path $mysqlBin 'mysql.exe') --protocol=TCP -h 127.0.0.1 -P 13306 -u root ("--password=" + $env.rootPassword) `
    -e "DROP DATABASE IF EXISTS $e2eDatabase; CREATE DATABASE $e2eDatabase CHARACTER SET utf8mb4;"
if ($LASTEXITCODE -ne 0) { throw "failed to reset $e2eDatabase" }

# --- 3. backend jar with demo profile --------------------------------------------
$jar = Join-Path $projectRoot 'backend/target/ai-teaching-backend-0.1.0-SNAPSHOT.jar'
# Always rebuild unless explicitly skipped: a stale jar silently fails new endpoints with 403.
if (-not $SkipBuild) {
    mvn -B -ntp -f (Join-Path $projectRoot 'backend/pom.xml') -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'backend package failed' }
}
if (-not (Test-Path $jar)) { throw 'backend jar missing' }
$demoPassword = [Guid]::NewGuid().ToString('N') + 'Aa1!'
$backendLog = Join-Path $work 'e2e-backend.log'
$backendEnv = @{
    DB_URL                = "jdbc:mysql://127.0.0.1:13306/${e2eDatabase}?sslMode=DISABLED&allowPublicKeyRetrieval=true"
    DB_USERNAME           = 'root'
    DB_PASSWORD           = $env.rootPassword
    SERVER_PORT           = "$BackendPort"
    SERVER_ADDRESS        = '127.0.0.1'
    USERNAME_ONLY_LOGIN_ENABLED = 'false'
    SESSION_COOKIE_SECURE = 'false'
    DEMO_PASSWORD         = $demoPassword
}
if ($classroomFixturePath) {
    New-Item -ItemType Directory -Path $classroomStorage | Out-Null
    $backendEnv['CLASSROOM_FFPROBE_PATH'] = $classroomFfprobePath
    $backendEnv['CLASSROOM_MOCK_MODULES_ENABLED'] = 'true'
    $backendEnv['FILE_STORAGE_DIR'] = $classroomStorage
}
foreach ($key in $backendEnv.Keys) { [Environment]::SetEnvironmentVariable($key, $backendEnv[$key], 'Process') }
$backend = $null
try {
    $backend = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar, '--spring.profiles.active=demo') -PassThru `
        -RedirectStandardOutput $backendLog -RedirectStandardError (Join-Path $work 'e2e-backend.err.log') `
        -WindowStyle Hidden -WorkingDirectory $projectRoot
    [Environment]::SetEnvironmentVariable('DEV_API_TARGET', "http://127.0.0.1:$BackendPort", 'Process')
    $up = $false
    foreach ($i in 1..90) {
        Start-Sleep -Seconds 2
        try {
            $status = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$BackendPort/api/v1/system/status" -TimeoutSec 3
            if ($status.StatusCode -eq 200 -and $status.Content -match 'UP') { $up = $true; break }
        } catch { }
    }
    if (-not $up) { throw 'backend did not become UP in time' }
    Write-Host "backend UP on $BackendPort (pid $($backend.Id))"

    # --- 4. Vite dev server --------------------------------------------------------
    $viteLog = Join-Path $work 'e2e-vite.log'
    $vite = Start-Process -FilePath 'npm.cmd' -ArgumentList @('run', 'dev', '--', "--port=$FrontendPort", '--strictPort') `
        -PassThru -RedirectStandardOutput $viteLog -RedirectStandardError (Join-Path $work 'e2e-vite.err.log') `
        -WindowStyle Hidden -WorkingDirectory (Join-Path $projectRoot 'frontend')
    try {
        $viteUp = $false
        foreach ($i in 1..60) {
            Start-Sleep -Seconds 1
            try {
                $page = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$FrontendPort/" -TimeoutSec 3
                if ($page.StatusCode -eq 200) { $viteUp = $true; break }
            } catch { }
        }
        if (-not $viteUp) { throw 'vite did not start in time' }
        Write-Host "vite UP on $FrontendPort (pid $($vite.Id))"

        # --- 5. Playwright ----------------------------------------------------------
        $env:E2E_DEMO_PASSWORD = $demoPassword
        $env:E2E_BASE_URL = "http://127.0.0.1:$FrontendPort"
        [Environment]::SetEnvironmentVariable('E2E_CLASSROOM_MEDIA_FIXTURE', $classroomFixturePath, 'Process')
        Push-Location (Join-Path $projectRoot 'frontend')
        try {
            npx playwright test
            if ($LASTEXITCODE -ne 0) { throw "playwright failed with exit code $LASTEXITCODE" }
        } finally {
            Pop-Location
        }
        Write-Host 'BROWSER E2E: PASS'
    } finally {
        if (-not $vite.HasExited) { Stop-Process -Id $vite.Id -Force -ErrorAction SilentlyContinue }
    }
} finally {
    if ($backend -and -not $backend.HasExited) { Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue }
    foreach ($key in $previousClassroomEnv.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previousClassroomEnv[$key], 'Process')
    }
    if ($classroomStorage -and (Test-Path -LiteralPath $classroomStorage)) {
        $resolvedWork = [IO.Path]::GetFullPath($work).TrimEnd('\','/')
        $resolvedStorage = [IO.Path]::GetFullPath($classroomStorage).TrimEnd('\','/')
        if (-not $resolvedStorage.StartsWith($resolvedWork + [IO.Path]::DirectorySeparatorChar,
                [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Classroom E2E storage escaped the project work directory; cleanup refused.'
        }
        Remove-Item -LiteralPath $resolvedStorage -Recurse -Force
    }
}
