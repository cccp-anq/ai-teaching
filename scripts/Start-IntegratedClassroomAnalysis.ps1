[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$EnableUsernameOnlyLogin
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$configPath = Join-Path $projectRoot '.work\integrated-classroom-analysis.json'
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw 'Missing .work/integrated-classroom-analysis.json. Run Initialize-IntegratedClassroomAnalysis.ps1 first.'
}
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
if ($config.schemaVersion -ne 1 -or -not $config.runtime -or -not $config.transcription -or -not $config.headup) {
    throw 'Integrated classroom analysis configuration has an unsupported shape.'
}
foreach ($required in @('javaPath','pythonPath','ffmpegPath','ffprobePath','transcriptionToken','headupToken','mediaSigningKey')) {
    if ([string]::IsNullOrWhiteSpace([string]$config.runtime.$required)) { throw "Integrated runtime value is missing: $required" }
}
foreach ($path in @($config.runtime.javaPath,$config.runtime.pythonPath,$config.runtime.ffmpegPath,$config.runtime.ffprobePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Integrated runtime file is missing: $path" }
}

& (Join-Path $PSScriptRoot 'Stop-IntegratedClassroomAnalysis.ps1')
try {
if (-not $SkipBuild) {
    & mvn -q -f (Join-Path $projectRoot 'pom.xml') -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Integrated Java build failed.' }
}
& $config.runtime.pythonPath -c "import PIL, sqlite3, sys; sys.path.insert(0, sys.argv[1]); import headup" (Join-Path $projectRoot 'modules\headup\src')
if ($LASTEXITCODE -ne 0) { throw 'Integrated head-up Python runtime is incomplete.' }

function To-Environment($section) {
    $values = @{}
    foreach ($property in $section.PSObject.Properties) { $values[$property.Name] = [string]$property.Value }
    return $values
}
function Assert-PortFree([int]$port) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$port)
    try { $listener.Start() } catch { throw "Loopback port $port is already occupied by an unmanaged process." } finally { $listener.Stop() }
}
function Wait-Health([string]$url,[string]$token,[string]$expected,[System.Diagnostics.Process]$process,[string]$logPath) {
    foreach ($attempt in 1..120) {
        $process.Refresh()
        if ($process.HasExited) { throw "$expected module exited during startup; inspect $logPath" }
        try {
            $health = Invoke-RestMethod -Uri $url -Headers @{Authorization="Bearer $token"} -TimeoutSec 2
            if ($health.status -eq 'UP' -and $health.moduleType -eq $expected) { return $health }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "$expected module did not become healthy; inspect $logPath"
}

Assert-PortFree 18082
Assert-PortFree 18083
Assert-PortFree 8080
$runRoot = Join-Path $projectRoot '.work\integrated-classroom-analysis'
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null

$transcriptionEnv = To-Environment $config.transcription
$transcriptionEnv['SERVER_PORT'] = '18082'
$transcriptionEnv['MODULE_BEARER_TOKENS'] = [string]$config.runtime.transcriptionToken
$transcriptionEnv['MODULE_PROVIDER_ID'] = 'transcription-bundled'
$transcriptionEnv['MODULE_VERSION'] = 'transcription-bundled-1.0.0'
$transcriptionEnv['APP_RECOGNITION_ENABLED'] = 'true'
$transcriptionEnv['DB_URL'] = 'jdbc:h2:file:' + ((Join-Path $runRoot 'transcription').Replace('\','/')) + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;AUTO_SERVER=FALSE'
$transcriptionEnv['DB_USERNAME'] = 'sa'
$transcriptionEnv['DB_PASSWORD'] = ''
$transcriptionEnv['MEDIA_WORK_DIR'] = Join-Path $runRoot 'transcription-media'
$transcriptionEnv['APP_UPLOAD_TEMP'] = Join-Path $runRoot 'transcription-upload'
$transcriptionEnv['MEDIA_ALLOW_LOOPBACK_HTTP'] = 'true'
$transcriptionEnv['MEDIA_HOST_ALLOW_LIST'] = '127.0.0.1'
$transcriptionEnv['FFMPEG_PATH'] = [string]$config.runtime.ffmpegPath
$transcriptionEnv['FFPROBE_PATH'] = [string]$config.runtime.ffprobePath
$transcriptionJar = Join-Path $projectRoot 'modules\transcription\target\transcription-module-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $transcriptionJar)) { throw 'Integrated transcription package is missing.' }
$transcriptionOut = Join-Path $runRoot 'transcription.stdout.log'
$transcriptionErr = Join-Path $runRoot 'transcription.stderr.log'
$transcription = Start-Process -FilePath $config.runtime.javaPath -ArgumentList @('-Dfile.encoding=UTF-8','-jar',$transcriptionJar) `
    -Environment $transcriptionEnv -WorkingDirectory (Join-Path $projectRoot 'modules\transcription') `
    -WindowStyle Hidden -RedirectStandardOutput $transcriptionOut -RedirectStandardError $transcriptionErr -PassThru
Set-Content -LiteralPath (Join-Path $projectRoot '.work\integrated-transcription.pid') -Value $transcription.Id -Encoding ascii

$headupEnv = To-Environment $config.headup
$headupEnv['SERVER_HOST'] = '127.0.0.1'
$headupEnv['SERVER_PORT'] = '18083'
$headupEnv['MODULE_BEARER_TOKENS'] = [string]$config.runtime.headupToken
$headupEnv['MODULE_PROVIDER_ID'] = 'head-up-bundled'
$headupEnv['MODULE_VERSION'] = 'head-up-bundled-1.0.0'
$headupEnv['HEADUP_DEFINITION_ID'] = 'head-up-attendance-qwen-vl-v2'
$headupEnv['DB_PATH'] = Join-Path $runRoot 'headup.sqlite3'
$headupEnv['WORK_DIR'] = Join-Path $runRoot 'headup-work'
$headupEnv['MEDIA_ALLOW_LOOPBACK_HTTP'] = 'true'
$headupEnv['MEDIA_HOST_ALLOW_LIST'] = '127.0.0.1'
$headupEnv['MERGE_MODE'] = ''
$headupEnv['FFMPEG_PATH'] = [string]$config.runtime.ffmpegPath
$headupEnv['FFPROBE_PATH'] = [string]$config.runtime.ffprobePath
$headupEnv['PYTHONPATH'] = Join-Path $projectRoot 'modules\headup\src'
$headupEnv['PYTHONUTF8'] = '1'
$headupOut = Join-Path $runRoot 'headup.stdout.log'
$headupErr = Join-Path $runRoot 'headup.stderr.log'
$headup = Start-Process -FilePath $config.runtime.pythonPath -ArgumentList @('-m','headup','serve') `
    -Environment $headupEnv -WorkingDirectory (Join-Path $projectRoot 'modules\headup') `
    -WindowStyle Hidden -RedirectStandardOutput $headupOut -RedirectStandardError $headupErr -PassThru
Set-Content -LiteralPath (Join-Path $projectRoot '.work\integrated-headup.pid') -Value $headup.Id -Encoding ascii

$transcriptionHealth = Wait-Health 'http://127.0.0.1:18082/api/v1/health' $config.runtime.transcriptionToken 'TRANSCRIPTION' $transcription $transcriptionErr
$headupHealth = Wait-Health 'http://127.0.0.1:18083/api/v1/health' $config.runtime.headupToken 'HEAD_UP' $headup $headupErr
if ($transcriptionHealth.providerId -ne 'transcription-bundled' -or $transcriptionHealth.moduleVersion -ne 'transcription-bundled-1.0.0') {
    throw 'Bundled transcription identity is inconsistent.'
}
if ($headupHealth.providerId -ne 'head-up-bundled' -or $headupHealth.moduleVersion -ne 'head-up-bundled-1.0.0' -or
    $headupHealth.definitionId -ne 'head-up-attendance-qwen-vl-v2') {
    throw 'Bundled head-up identity or definition is inconsistent.'
}

Set-ExecutionPolicy -Scope Process Bypass
if ($EnableUsernameOnlyLogin) { . (Join-Path $PSScriptRoot 'Use-DevEnvironment.ps1') -EnableUsernameOnlyLogin }
else { . (Join-Path $PSScriptRoot 'Use-DevEnvironment.ps1') }
$env:CLASSROOM_MODULE_INTEGRATION_ENABLED = 'true'
$env:CLASSROOM_PUBLIC_BASE_URL = 'http://127.0.0.1:8080'
$env:CLASSROOM_MEDIA_SIGNING_KEY = [string]$config.runtime.mediaSigningKey
$env:TRANSCRIPTION_MODULE_BASE_URL = 'http://127.0.0.1:18082'
$env:TRANSCRIPTION_MODULE_BEARER_TOKEN = [string]$config.runtime.transcriptionToken
$env:TRANSCRIPTION_MODULE_PROVIDER_ID = 'transcription-bundled'
$env:TRANSCRIPTION_MODULE_VERSION = 'transcription-bundled-1.0.0'
$env:HEADUP_MODULE_BASE_URL = 'http://127.0.0.1:18083'
$env:HEADUP_MODULE_BEARER_TOKEN = [string]$config.runtime.headupToken
$env:HEADUP_MODULE_PROVIDER_ID = 'head-up-bundled'
$env:HEADUP_MODULE_VERSION = 'head-up-bundled-1.0.0'
$env:HEADUP_MODULE_DEFINITION_ID = 'head-up-attendance-qwen-vl-v2'
$env:CLASSROOM_FFPROBE_PATH = [string]$config.runtime.ffprobePath
if (-not [string]::IsNullOrWhiteSpace([string]$config.report.CLASSROOM_REPORT_API_KEY)) {
    $env:CLASSROOM_REPORT_ENABLED = 'true'
    $env:CLASSROOM_REPORT_BASE_URL = [string]$config.report.CLASSROOM_REPORT_BASE_URL
    $env:CLASSROOM_REPORT_MODEL = [string]$config.report.CLASSROOM_REPORT_MODEL
    $env:CLASSROOM_REPORT_API_KEY = [string]$config.report.CLASSROOM_REPORT_API_KEY
}
$backendJar = Join-Path $projectRoot 'backend\target\ai-teaching-backend-0.1.0-SNAPSHOT.jar'
$backendOut = Join-Path $projectRoot '.work\app-backend.stdout.log'
$backendErr = Join-Path $projectRoot '.work\app-backend.stderr.log'
$backend = Start-Process -FilePath $config.runtime.javaPath -ArgumentList @('-Dfile.encoding=UTF-8','-jar',$backendJar) `
    -WorkingDirectory (Join-Path $projectRoot 'backend') -WindowStyle Hidden `
    -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr -PassThru
Set-Content -LiteralPath (Join-Path $projectRoot '.work\app-backend.pid') -Value $backend.Id -Encoding ascii
foreach ($attempt in 1..120) {
    $backend.Refresh()
    if ($backend.HasExited) { throw 'Main backend exited during startup; inspect .work/app-backend.stderr.log.' }
    try {
        $status = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/v1/system/status' -TimeoutSec 2
        if ($status.status -eq 'UP') {
            Write-Output 'Integrated transcription, head-up and main backend are healthy; no sibling project is used.'
            exit 0
        }
    } catch { }
    Start-Sleep -Milliseconds 500
}
throw 'Main backend did not become healthy; inspect .work/app-backend.stdout.log.'
} catch {
    $startupFailure = $_
    try { & (Join-Path $PSScriptRoot 'Stop-IntegratedClassroomAnalysis.ps1') } catch { }
    throw $startupFailure
}
