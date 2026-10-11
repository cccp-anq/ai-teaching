param([switch]$EnableUsernameOnlyLogin)

$ErrorActionPreference = 'Stop'
if ($MyInvocation.InvocationName -ne '.') {
    throw 'This script must be dot-sourced so its process environment remains available: . .\scripts\Use-DevEnvironment.ps1'
}
$projectRoot = Split-Path $PSScriptRoot
$settings = Get-Content (Join-Path $projectRoot '.work/dev-environment.json') -Raw | ConvertFrom-Json
$databaseUri = [Uri](([string]$settings.DB_URL).Substring(5))
if ($databaseUri.Host -notin @('127.0.0.1', 'localhost', '::1')) {
    throw 'Refusing to load the development environment because DB_URL is not loopback.'
}
foreach ($key in @('DB_URL','DB_USERNAME','DB_PASSWORD','SERVER_PORT','FILE_STORAGE_DIR','DEV_API_TARGET','VITE_API_BASE_URL')) {
    [Environment]::SetEnvironmentVariable($key, [string]$settings.$key, 'Process')
}
[Environment]::SetEnvironmentVariable('SESSION_COOKIE_SECURE', 'false', 'Process')
if ($EnableUsernameOnlyLogin) {
    [Environment]::SetEnvironmentVariable('SERVER_ADDRESS', '127.0.0.1', 'Process')
    [Environment]::SetEnvironmentVariable('USERNAME_ONLY_LOGIN_ENABLED', 'true', 'Process')
    [Environment]::SetEnvironmentVariable('USERNAME_ONLY_LOGIN_CONFIRMATION', 'LOCAL_DEVELOPMENT_ONLY', 'Process')
    Write-Warning 'Username-only login is enabled for this loopback development process; passwords will be ignored.'
} else {
    [Environment]::SetEnvironmentVariable('SERVER_ADDRESS', $null, 'Process')
    [Environment]::SetEnvironmentVariable('USERNAME_ONLY_LOGIN_ENABLED', 'false', 'Process')
    [Environment]::SetEnvironmentVariable('USERNAME_ONLY_LOGIN_CONFIRMATION', $null, 'Process')
}
Write-Output 'Loaded the loopback development environment into this process; no credentials printed.'
