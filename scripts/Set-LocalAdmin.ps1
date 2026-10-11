[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9._-]{3,64}$')]
    [string]$Username = 'local-admin',
    [switch]$GeneratePassword,
    [switch]$SaveCredential
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot
$workRoot = Join-Path $projectRoot '.work'
$settingsPath = Join-Path $workRoot 'dev-environment.json'
$jarPath = Join-Path $projectRoot 'backend/target/ai-teaching-backend-0.1.0-SNAPSHOT.jar'
$credentialPath = Join-Path $workRoot 'local-admin-credentials.txt'

if ($GeneratePassword -and -not $SaveCredential) {
    throw '-GeneratePassword requires -SaveCredential so the generated password is not lost.'
}
if (-not (Test-Path -LiteralPath $settingsPath)) {
    throw 'Local database configuration is missing. Run scripts/Start-DevDatabase.ps1 first.'
}
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw 'Backend package is missing. Run: mvn -B -ntp -f backend/pom.xml package'
}

$settings = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
$mysqlClient = Join-Path $settings.mysqlBin 'mysql.exe'
$clientOptions = Join-Path $workRoot 'mysql-client.cnf'
if (-not (Test-Path -LiteralPath $mysqlClient) -or -not (Test-Path -LiteralPath $clientOptions)) {
    throw 'Local MySQL client configuration is incomplete. Start the project database first.'
}

$actualDirectory = & $mysqlClient "--defaults-file=$clientOptions" --batch --skip-column-names -e 'SELECT @@datadir'
if ($LASTEXITCODE -ne 0) { throw 'Cannot connect to the project MySQL instance.' }
$expectedDirectory = [IO.Path]::GetFullPath((Join-Path $workRoot 'mysql-dev')).TrimEnd('\','/')
$actualDirectory = [IO.Path]::GetFullPath(($actualDirectory -join '').Trim()).TrimEnd('\','/')
if ($actualDirectory -ne $expectedDirectory) {
    throw 'Connected MySQL is not the project-isolated instance; account configuration refused.'
}

function New-StrongLocalPassword {
    $bytes = [byte[]]::new(32)
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return ([Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','.').Replace('/','_') + '!Aa1')
}

$plainPassword = $null
$passwordPointer = [IntPtr]::Zero
if ($GeneratePassword) {
    $plainPassword = New-StrongLocalPassword
} else {
    $securePassword = Read-Host 'Enter a local administrator password with at least 12 characters' -AsSecureString
    try {
        $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    } finally {
        if ($passwordPointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
        }
    }
}
if ([string]::IsNullOrWhiteSpace($plainPassword) -or $plainPassword.Length -lt 12) {
    throw 'Password must contain at least 12 characters.'
}

$environmentKeys = @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'FILE_STORAGE_DIR', 'SESSION_COOKIE_SECURE', 'LOCAL_ADMIN_USERNAME', 'LOCAL_ADMIN_PASSWORD')
$previous = @{}
foreach ($key in $environmentKeys) {
    $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
try {
    $env:DB_URL = [string]$settings.DB_URL
    $env:DB_USERNAME = [string]$settings.DB_USERNAME
    $env:DB_PASSWORD = [string]$settings.DB_PASSWORD
    $env:FILE_STORAGE_DIR = [string]$settings.FILE_STORAGE_DIR
    $env:SESSION_COOKIE_SECURE = 'false'
    $env:LOCAL_ADMIN_USERNAME = $Username
    $env:LOCAL_ADMIN_PASSWORD = $plainPassword
    & java -jar $jarPath '--spring.profiles.active=local-admin-bootstrap'
    if ($LASTEXITCODE -ne 0) { throw 'Local administrator bootstrap failed.' }

    if ($SaveCredential) {
        $utf8 = [Text.UTF8Encoding]::new($false)
        $content = "username=$Username`npassword=$plainPassword`ndatabase=ai_teaching@127.0.0.1:$($settings.port)`nupdatedAt=$([DateTimeOffset]::Now.ToString('o'))`n"
        [IO.File]::WriteAllText($credentialPath, $content, $utf8)
        Write-Output 'Local credential saved to .work/local-admin-credentials.txt (Git ignored; do not share it).'
    }
    Write-Output "Local administrator '$Username' is ready. The password was not printed."
} finally {
    $plainPassword = $null
    foreach ($key in $environmentKeys) {
        [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process')
    }
}
