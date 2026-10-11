[CmdletBinding()]
param([string]$ConfigPath)
$ErrorActionPreference = 'Stop'

# Local development helper only. It configures the current PowerShell process;
# the backend is the sole component that submits report requests.
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($ConfigPath)) {
    $ConfigPath = Join-Path $projectRoot '.work\integrated-classroom-analysis.json'
}
$resolvedConfig = (Resolve-Path -LiteralPath $ConfigPath -ErrorAction Stop).Path
$config = Get-Content -LiteralPath $resolvedConfig -Raw | ConvertFrom-Json
if (-not $config.report) { throw 'Integrated config has no report section.' }
$baseUrl = [string]$config.report.CLASSROOM_REPORT_BASE_URL
$model = [string]$config.report.CLASSROOM_REPORT_MODEL
$apiKey = [string]$config.report.CLASSROOM_REPORT_API_KEY
if ($baseUrl -ne 'https://dashscope.aliyuncs.com/compatible-mode/v1') {
    throw 'Cloud report requires the approved Aliyun HTTPS endpoint.'
}
if ($model -ne 'qwen3.7-plus') { throw 'Cloud report requires the configured qwen3.7-plus model.' }
if ([string]::IsNullOrWhiteSpace($apiKey) -or $apiKey.Contains("`r") -or $apiKey.Contains("`n")) {
    throw 'Integrated report API key is missing or invalid.'
}

[Environment]::SetEnvironmentVariable('CLASSROOM_REPORT_BASE_URL', $baseUrl, 'Process')
[Environment]::SetEnvironmentVariable('CLASSROOM_REPORT_MODEL', $model, 'Process')
[Environment]::SetEnvironmentVariable('CLASSROOM_REPORT_API_KEY', $apiKey, 'Process')
[Environment]::SetEnvironmentVariable('CLASSROOM_REPORT_ENABLED', 'true', 'Process')
$apiKey = $null
Write-Output 'Cloud classroom report is enabled for backend processes started from this terminal; credentials were not printed. Existing queued reports may be submitted and charged.'
