[CmdletBinding()]
param([string]$ConfigPath, [switch]$Rehearsal)
$ErrorActionPreference = 'Stop'

if ($Rehearsal) {
    if ($ConfigPath) { throw 'Rehearsal does not read integrated config or use an API key.' }
    $previousRehearsal = [Environment]::GetEnvironmentVariable('CLASSROOM_REPORT_AUDIT_REHEARSAL', 'Process')
    try {
        $env:CLASSROOM_REPORT_AUDIT_REHEARSAL = 'true'
        Write-Output 'Rehearsing retained report audit with a loopback fake model; no paid request.'
        & (Join-Path $PSScriptRoot 'Test-MigrationDatabase.ps1') -TestClass 'ClassroomAnalysisIntegrationTest#retainedSyntheticReportAuditRehearsal'
        if ($LASTEXITCODE -ne 0) { throw 'Report audit rehearsal failed; inspect its retained isolated MySQL schema.' }
    } finally {
        [Environment]::SetEnvironmentVariable('CLASSROOM_REPORT_AUDIT_REHEARSAL', $previousRehearsal, 'Process')
    }
    return
}
if ([string]::IsNullOrWhiteSpace($ConfigPath)) {
    $ConfigPath = Join-Path ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))) '.work\integrated-classroom-analysis.json'
}
$resolvedConfig = (Resolve-Path -LiteralPath $ConfigPath -ErrorAction Stop).Path
$config = Get-Content -LiteralPath $resolvedConfig -Raw | ConvertFrom-Json
if (-not $config.report) { throw 'Integrated config has no report section.' }
$baseUrl = [string]$config.report.CLASSROOM_REPORT_BASE_URL
$model = [string]$config.report.CLASSROOM_REPORT_MODEL
$apiKey = [string]$config.report.CLASSROOM_REPORT_API_KEY
if ($baseUrl -ne 'https://dashscope.aliyuncs.com/compatible-mode/v1') {
    throw 'Live probe requires the approved Aliyun HTTPS endpoint.'
}
if ($model -ne 'qwen3.7-plus') { throw 'Live probe requires the configured qwen3.7-plus model.' }
if ([string]::IsNullOrWhiteSpace($apiKey)) { throw 'Integrated report API key is empty.' }

$previousLive = [Environment]::GetEnvironmentVariable('CLASSROOM_REPORT_LIVE_TEST', 'Process')
$previousKey = [Environment]::GetEnvironmentVariable('CLASSROOM_REPORT_API_KEY', 'Process')
try {
    $env:CLASSROOM_REPORT_LIVE_TEST = 'true'
    $env:CLASSROOM_REPORT_API_KEY = $apiKey
    Write-Output 'Running one paid report probe with synthetic classroom evidence; no retry.'
    & (Join-Path $PSScriptRoot 'Test-MigrationDatabase.ps1') -TestClass 'ClassroomAnalysisIntegrationTest#liveSyntheticReportUsesAliyunOnce'
    if ($LASTEXITCODE -ne 0) { throw 'Live report probe failed; inspect the retained isolated MySQL schema before any further call.' }
} finally {
    [Environment]::SetEnvironmentVariable('CLASSROOM_REPORT_LIVE_TEST', $previousLive, 'Process')
    [Environment]::SetEnvironmentVariable('CLASSROOM_REPORT_API_KEY', $previousKey, 'Process')
    $apiKey = $null
}
