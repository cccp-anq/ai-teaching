# Explicit, one-media paid integration check against the two bundled modules.
# Reads credentials from this project's private integrated config into child processes only.
[CmdletBinding()]
param(
    [string]$ConfigPath,
    [string]$SyntheticMediaPath,
    [switch]$WithReport,
    [int]$TranscriptionPort = 18180,
    [int]$HeadUpPort = 18181
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$defaultConfigPath = Join-Path $projectRoot '.work\integrated-classroom-analysis.json'
if ([string]::IsNullOrWhiteSpace($ConfigPath)) { $ConfigPath = $defaultConfigPath }
if ([string]::IsNullOrWhiteSpace($SyntheticMediaPath)) {
    $SyntheticMediaPath = Join-Path $projectRoot 'modules\headup\docs\fixtures\head-up-module-v0.1\media\headup-synthetic-classroom-20s.mp4'
}
$configPath = (Resolve-Path -LiteralPath $ConfigPath -ErrorAction Stop).Path
$fixturePath = (Resolve-Path -LiteralPath $SyntheticMediaPath -ErrorAction Stop).Path
if ((Split-Path $fixturePath -Leaf) -ne 'headup-synthetic-classroom-20s.mp4') {
    throw 'Paid integration accepts only the named synthetic 20-second fixture.'
}
# Pinned to the reviewed bundled media manifest, not a caller-supplied name.
$expectedFixtureSha256 = 'a6a3f7988b254d8943e95f495c6ca53ca0e09060df4eeb402cd29e5a18a137c9'
if ((Get-FileHash -LiteralPath $fixturePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedFixtureSha256) {
    throw 'Synthetic media hash does not match the reviewed fixture; no paid request was made.'
}
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
if (-not $config.transcription.DASHSCOPE_API_KEY -or -not $config.transcription.OSS_ACCESS_KEY_ID -or
    -not $config.transcription.OSS_ACCESS_KEY_SECRET -or -not $config.headup.DASHSCOPE_API_KEY) {
    throw 'ASR/OSS/vision credentials are incomplete; no process was started.'
}
if ($config.transcription.ASR_PROVIDER -ne 'aliyun-filetrans' -or $config.headup.VISION_PROVIDER -ne 'qwen-vl') {
    throw 'This bounded integration script requires the reviewed Aliyun ASR and Qwen VL providers.'
}
if ($WithReport -and ($config.report.CLASSROOM_REPORT_BASE_URL -ne 'https://dashscope.aliyuncs.com/compatible-mode/v1' -or
        $config.report.CLASSROOM_REPORT_MODEL -ne 'qwen3.7-plus' -or
        [string]::IsNullOrWhiteSpace([string]$config.report.CLASSROOM_REPORT_API_KEY))) {
    throw 'Full-chain probe requires the approved Aliyun endpoint, qwen3.7-plus and a report key; no paid request was made.'
}
$jar = Join-Path $projectRoot 'modules\transcription\target\transcription-module-0.1.0-SNAPSHOT.jar'
$ffmpeg = [string]$config.runtime.ffmpegPath
$ffprobe = [string]$config.runtime.ffprobePath
foreach ($path in @($jar, $config.runtime.javaPath, $config.runtime.pythonPath, $ffmpeg, $ffprobe)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required executable or jar is missing: $path" }
}
foreach ($port in @($TranscriptionPort, $HeadUpPort)) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $listener.Start() } finally { $listener.Stop() }
}
$runRoot = Join-Path $projectRoot ('.work/real-module-e2e-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
# Work only from a private verified copy so a changed source path cannot be muxed
# after the identity check and sent to the paid providers.
$verifiedFixture = Join-Path $runRoot 'verified-synthetic-classroom-20s.mp4'
try {
    Copy-Item -LiteralPath $fixturePath -Destination $verifiedFixture -ErrorAction Stop
    $actualFixtureSha256 = (Get-FileHash -LiteralPath $verifiedFixture -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualFixtureSha256 -ne $expectedFixtureSha256) {
        throw 'Synthetic media hash does not match the reviewed fixture; no paid request was made.'
    }
} catch {
    if (Test-Path -LiteralPath $verifiedFixture) { Remove-Item -LiteralPath $verifiedFixture -Force }
    throw
}
# The merge fixture has a sine-wave audio track, which the real ASR correctly
# rejects as having no words. Synthesize anonymous classroom speech locally.
$speechPath = Join-Path $runRoot 'synthetic-speech.wav'
$speechMediaPath = Join-Path $runRoot 'synthetic-spoken-classroom-20s.mp4'
Add-Type -AssemblyName System.Speech
$synthesizer = [System.Speech.Synthesis.SpeechSynthesizer]::new()
try {
    $chineseVoice = $synthesizer.GetInstalledVoices() |
        Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -eq 'zh-CN' } |
        Select-Object -First 1
    if (-not $chineseVoice) { throw 'A local zh-CN speech voice is required before any paid request.' }
    $synthesizer.SelectVoice($chineseVoice.VoiceInfo.Name)
    $synthesizer.SetOutputToWaveFile($speechPath)
    $synthesizer.Speak('同学们，今天我们学习分数的基本概念。请大家观察黑板上的例题。二分之一表示把一个整体平均分成两份，取其中的一份。')
    $synthesizer.SetOutputToNull()
} finally {
    $synthesizer.Dispose()
}
& $ffmpeg -hide_banner -loglevel error -nostdin -y -i $verifiedFixture -i $speechPath `
    -map '0:v:0' -map '1:a:0' -c:v copy -c:a aac -af apad -t 20 -movflags '+faststart' $speechMediaPath
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $speechMediaPath -PathType Leaf) -or
    (Get-Item -LiteralPath $speechMediaPath).Length -lt 10000) {
    throw 'Local spoken synthetic media preparation failed; no paid request was made.'
}
$transcriptionToken = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$headUpToken = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))

$transcriptionEnv = @{}
foreach ($item in $config.transcription.PSObject.Properties) { $transcriptionEnv[$item.Name] = [string]$item.Value }
$transcriptionEnv['SERVER_PORT'] = [string]$TranscriptionPort
$transcriptionEnv['MODULE_BEARER_TOKENS'] = $transcriptionToken
$transcriptionEnv['MODULE_PROVIDER_ID'] = 'transcription-local'
$transcriptionEnv['MODULE_VERSION'] = 'transcription-1.0.0'
$transcriptionEnv['APP_COMPATIBILITY_API'] = 'true'
$transcriptionEnv['MERGE_MODE'] = '1'
$transcriptionEnv['APP_RECOGNITION_ENABLED'] = 'true'
$transcriptionEnv['APP_ASR_MAX_CALLS'] = $(if ($WithReport) { '1' } else { '2' })
$transcriptionEnv['APP_REPORT_ENABLED'] = 'false'
$transcriptionEnv['APP_INTERACTION_ENABLED'] = 'false'
$transcriptionEnv['REPORT_API_KEY'] = ''
$transcriptionDb = (Join-Path $runRoot 'transcription').Replace('\', '/')
$transcriptionEnv['DB_URL'] = "jdbc:h2:file:$transcriptionDb;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;AUTO_SERVER=FALSE"
$transcriptionEnv['APP_MEDIA_DIR'] = Join-Path $runRoot 'app-media'
$transcriptionEnv['APP_UPLOAD_TEMP'] = Join-Path $runRoot 'uploads'
$transcriptionEnv['MEDIA_WORK_DIR'] = Join-Path $runRoot 'asr-media'
$transcriptionEnv['MEDIA_ALLOW_LOOPBACK_HTTP'] = 'true'
$transcriptionEnv['MEDIA_HOST_ALLOW_LIST'] = '127.0.0.1'
$transcriptionEnv['FFMPEG_PATH'] = $ffmpeg
$transcriptionEnv['FFPROBE_PATH'] = $ffprobe

$headUpEnv = @{}
foreach ($item in $config.headup.PSObject.Properties) { $headUpEnv[$item.Name] = [string]$item.Value }
$headUpEnv['SERVER_HOST'] = '127.0.0.1'
$headUpEnv['SERVER_PORT'] = [string]$HeadUpPort
$headUpEnv['MODULE_BEARER_TOKENS'] = $headUpToken
$headUpEnv['MODULE_PROVIDER_ID'] = 'head-up-local'
$headUpEnv['MODULE_VERSION'] = 'head-up-0.1.0'
$headUpEnv['HEADUP_DEFINITION_ID'] = 'head-up-qwen-vl-v1'
$headUpEnv['DB_PATH'] = Join-Path $runRoot 'headup.sqlite3'
$headUpEnv['WORK_DIR'] = Join-Path $runRoot 'headup-work'
$headUpEnv['MEDIA_ALLOW_LOOPBACK_HTTP'] = 'true'
$headUpEnv['MEDIA_HOST_ALLOW_LIST'] = '127.0.0.1'
$headUpEnv['MERGE_MODE'] = ''
$headUpEnv['FFMPEG_PATH'] = $ffmpeg
$headUpEnv['FFPROBE_PATH'] = $ffprobe
$headUpEnv['MAX_WINDOWS'] = '2'
$headUpEnv['HEADUP_COUNTS_RETRY_EXTRA'] = '0'
$headUpEnv['VISION_MAX_RETRIES'] = '0'
$headUpEnv['VISION_FALLBACK_MODEL'] = [string]$config.headup.VISION_MODEL
$headUpEnv['HEADUP_REVIEW_ENABLED'] = 'false'

$testEnv = @{
    REAL_CLASSROOM_MODULE_E2E = 'true'
    REAL_CLASSROOM_MEDIA_FIXTURE = $speechMediaPath
    REAL_CLASSROOM_FFPROBE = $ffprobe
    REAL_TRANSCRIPTION_URL = "http://127.0.0.1:$TranscriptionPort"
    REAL_TRANSCRIPTION_TOKEN = $transcriptionToken
    REAL_HEADUP_URL = "http://127.0.0.1:$HeadUpPort"
    REAL_HEADUP_TOKEN = $headUpToken
}
if ($WithReport) {
    $testEnv['REAL_CLASSROOM_FULL_E2E'] = 'true'
    $testEnv['REAL_CLASSROOM_AUDIT_ROOT'] = $runRoot
    $testEnv['REAL_CLASSROOM_REPORT_API_KEY'] = [string]$config.report.CLASSROOM_REPORT_API_KEY
}
$previous = @{}
foreach ($name in $testEnv.Keys) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$transcription = $null
$headUp = $null
try {
    $transcription = Start-Process -FilePath $config.runtime.javaPath -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', $jar) `
        -Environment $transcriptionEnv -WindowStyle Hidden -PassThru -WorkingDirectory $runRoot `
        -RedirectStandardOutput (Join-Path $runRoot 'transcription.stdout.log') `
        -RedirectStandardError (Join-Path $runRoot 'transcription.stderr.log')
    $headUp = Start-Process -FilePath $config.runtime.pythonPath -ArgumentList @('-m', 'headup', 'serve') `
        -Environment ($headUpEnv + @{ PYTHONPATH = (Join-Path $projectRoot 'modules\headup\src'); PYTHONUTF8 = '1' }) `
        -WindowStyle Hidden -PassThru -WorkingDirectory (Join-Path $projectRoot 'modules\headup') `
        -RedirectStandardOutput (Join-Path $runRoot 'headup.stdout.log') `
        -RedirectStandardError (Join-Path $runRoot 'headup.stderr.log')
    foreach ($target in @(
        @{ Url = "http://127.0.0.1:$TranscriptionPort/api/v1/health"; Token = $transcriptionToken; Module = 'TRANSCRIPTION' },
        @{ Url = "http://127.0.0.1:$HeadUpPort/api/v1/health"; Token = $headUpToken; Module = 'HEAD_UP' }
    )) {
        $ready = $false
        $health = $null
        foreach ($attempt in 1..40) {
            try {
                $response = Invoke-WebRequest -Uri $target.Url -Headers @{ Authorization = "Bearer $($target.Token)" } -TimeoutSec 2
                if ($response.StatusCode -eq 200) {
                    $health = $response.Content | ConvertFrom-Json
                    $ready = $true
                    break
                }
            } catch { Start-Sleep -Milliseconds 500 }
        }
        if (-not $ready) { throw 'A real module did not reach authenticated health; paid job was not submitted.' }
        if ($health.status -ne 'UP' -or $health.moduleType -ne $target.Module -or
            ($target.Module -eq 'TRANSCRIPTION' -and
                ($health.providerId -ne 'transcription-local' -or
                 $health.moduleVersion -ne 'transcription-1.0.0' -or
                 $health.timeBase -ne 'MEDIA_START_MS' -or
                 $health.capabilities.languages -notcontains 'zh-CN')) -or
            ($target.Module -eq 'HEAD_UP' -and
                ($health.providerId -ne 'head-up-local' -or
                 $health.moduleVersion -ne 'head-up-0.1.0' -or
                 $health.definitionId -ne 'head-up-qwen-vl-v1' -or
                 $health.outputModes -notcontains 'COUNTS' -or
                 -not $health.auth.tokensConfigured))) {
            throw 'A module health identity or capability does not match the main project; paid job was not submitted.'
        }
    }
    foreach ($name in $testEnv.Keys) {
        [Environment]::SetEnvironmentVariable($name, $testEnv[$name], 'Process')
    }
    if ($WithReport) {
        Write-Output 'Both real modules are healthy; submitting one anonymous synthetic media and at most one paid AI report request. No automatic retry.'
    } else {
        Write-Output 'Both real modules are healthy; submitting exactly one anonymous synthetic classroom media with locally generated speech.'
    }
    if ($WithReport) {
        & (Join-Path $PSScriptRoot 'Test-MigrationDatabase.ps1') `
            -TestClass 'ClassroomModuleOrchestrationIntegrationTest#explicitlyConfiguredRealModulesProcessOneSyntheticMedia'
    } else {
        & mvn -B -ntp -f (Join-Path $projectRoot 'backend/pom.xml') `
            '-Dtest=ClassroomModuleOrchestrationIntegrationTest#explicitlyConfiguredRealModulesProcessOneSyntheticMedia' test
    }
    if ($LASTEXITCODE -ne 0) { throw 'Paid module integration failed; inspect the saved local test/module logs before any retry.' }
    Write-Output $(if ($WithReport) { 'Real-module and report synthetic pipeline: PASS' } else { 'Real-module synthetic media integration: PASS' })
} finally {
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
    foreach ($process in @($headUp, $transcription)) {
        if ($process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            Wait-Process -Id $process.Id -Timeout 10 -ErrorAction SilentlyContinue
        }
    }
    Write-Output "Local test data and diagnostic logs retained at $runRoot"
}
