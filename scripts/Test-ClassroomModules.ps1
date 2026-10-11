[CmdletBinding()]
param(
    [string]$TranscriptionBaseUrl = $env:TRANSCRIPTION_MODULE_BASE_URL,
    [string]$TranscriptionBearerToken = $env:TRANSCRIPTION_MODULE_BEARER_TOKEN,
    [string]$TranscriptionProviderId = $env:TRANSCRIPTION_MODULE_PROVIDER_ID,
    [string]$TranscriptionModuleVersion = $env:TRANSCRIPTION_MODULE_VERSION,
    [string]$HeadUpBaseUrl = $env:HEADUP_MODULE_BASE_URL,
    [string]$HeadUpBearerToken = $env:HEADUP_MODULE_BEARER_TOKEN,
    [string]$HeadUpProviderId = $env:HEADUP_MODULE_PROVIDER_ID,
    [string]$HeadUpModuleVersion = $env:HEADUP_MODULE_VERSION,
    [string]$HeadUpDefinitionId = $(if ($env:HEADUP_MODULE_DEFINITION_ID) { $env:HEADUP_MODULE_DEFINITION_ID } else { 'head-up-attendance-qwen-vl-v2' }),
    [int]$TimeoutSec = 10
)
$ErrorActionPreference = 'Stop'

function Assert-InternalEndpoint([string]$name, [string]$raw) {
    if ([string]::IsNullOrWhiteSpace($raw)) { throw "$name base URL is required." }
    $uri = $null
    if (-not [Uri]::TryCreate($raw, [UriKind]::Absolute, [ref]$uri) -or [string]::IsNullOrWhiteSpace($uri.Host)) {
        throw "$name base URL is invalid."
    }
    if ($uri.UserInfo -or $uri.Query -or $uri.Fragment) { throw "$name base URL must not contain credentials, query or fragment." }
    $loopbackHttp = $uri.Scheme -eq 'http' -and ($uri.Host -eq '127.0.0.1' -or $uri.Host -eq 'localhost')
    if ($uri.Scheme -ne 'https' -and -not $loopbackHttp) { throw "$name base URL must use HTTPS or loopback HTTP." }
    return $raw.TrimEnd('/')
}

function Assert-Configured([string]$name, [string]$value) {
    if ([string]::IsNullOrWhiteSpace($value)) { throw "$name is required." }
}

function Get-ModuleHealth([string]$name, [string]$baseUrl, [string]$token) {
    try {
        return Invoke-RestMethod -Method Get -Uri "$baseUrl/api/v1/health" `
            -Headers @{ Authorization = "Bearer $token"; Accept = 'application/json' } -TimeoutSec $TimeoutSec
    } catch {
        throw "$name health check failed; verify its address, process and Bearer configuration."
    }
}

function Assert-Equal([string]$name, $actual, $expected) {
    if ([string]$actual -cne [string]$expected) { throw "$name mismatch." }
}

$transcriptionUrl = Assert-InternalEndpoint 'TRANSCRIPTION' $TranscriptionBaseUrl
$headUpUrl = Assert-InternalEndpoint 'HEAD_UP' $HeadUpBaseUrl
Assert-Configured 'TRANSCRIPTION bearer token' $TranscriptionBearerToken
Assert-Configured 'TRANSCRIPTION provider ID' $TranscriptionProviderId
Assert-Configured 'TRANSCRIPTION module version' $TranscriptionModuleVersion
Assert-Configured 'HEAD_UP bearer token' $HeadUpBearerToken
Assert-Configured 'HEAD_UP provider ID' $HeadUpProviderId
Assert-Configured 'HEAD_UP module version' $HeadUpModuleVersion
Assert-Configured 'HEAD_UP definition ID' $HeadUpDefinitionId

$transcription = Get-ModuleHealth 'TRANSCRIPTION' $transcriptionUrl $TranscriptionBearerToken
Assert-Equal 'TRANSCRIPTION status' $transcription.status 'UP'
Assert-Equal 'TRANSCRIPTION module type' $transcription.moduleType 'TRANSCRIPTION'
Assert-Equal 'TRANSCRIPTION provider ID' $transcription.providerId $TranscriptionProviderId
Assert-Equal 'TRANSCRIPTION module version' $transcription.moduleVersion $TranscriptionModuleVersion
Assert-Equal 'TRANSCRIPTION schema version' $transcription.schemaVersion '1.0'
Assert-Equal 'TRANSCRIPTION time base' $transcription.timeBase 'MEDIA_START_MS'
if (@($transcription.capabilities.languages) -cnotcontains 'zh-CN') { throw 'TRANSCRIPTION does not declare zh-CN support.' }

$headUp = Get-ModuleHealth 'HEAD_UP' $headUpUrl $HeadUpBearerToken
Assert-Equal 'HEAD_UP status' $headUp.status 'UP'
Assert-Equal 'HEAD_UP module type' $headUp.moduleType 'HEAD_UP'
Assert-Equal 'HEAD_UP provider ID' $headUp.providerId $HeadUpProviderId
Assert-Equal 'HEAD_UP module version' $headUp.moduleVersion $HeadUpModuleVersion
Assert-Equal 'HEAD_UP API version' $headUp.apiVersion '1.0'
Assert-Equal 'HEAD_UP definition ID' $headUp.definitionId $HeadUpDefinitionId
if (@($headUp.outputModes) -cnotcontains 'COUNTS') { throw 'HEAD_UP does not declare COUNTS output support.' }
if (-not $headUp.auth.bearerRequired -or -not $headUp.auth.tokensConfigured) { throw 'HEAD_UP Bearer authentication is not ready.' }

@(
    [pscustomobject]@{ Module = 'TRANSCRIPTION'; Status = $transcription.status; ProviderId = $transcription.providerId; ModuleVersion = $transcription.moduleVersion; DefinitionId = '-' },
    [pscustomobject]@{ Module = 'HEAD_UP'; Status = $headUp.status; ProviderId = $headUp.providerId; ModuleVersion = $headUp.moduleVersion; DefinitionId = $headUp.definitionId }
) | Format-Table -AutoSize
Write-Output 'PASS: both classroom modules match the registered contract identities. No analysis job was submitted.'
