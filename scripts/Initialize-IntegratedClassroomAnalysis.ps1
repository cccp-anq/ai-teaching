[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SourceConfigPath,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workRoot = Join-Path $projectRoot '.work'
$targetPath = Join-Path $workRoot 'integrated-classroom-analysis.json'
$sourcePath = (Resolve-Path -LiteralPath $SourceConfigPath -ErrorAction Stop).Path
if ((Test-Path -LiteralPath $targetPath) -and -not $Force) {
    throw 'Integrated classroom analysis configuration already exists. Use -Force only when intentionally replacing it.'
}
$source = Get-Content -LiteralPath $sourcePath -Raw | ConvertFrom-Json
if ($source.schemaVersion -ne 1 -or -not $source.transcription -or -not $source.headup -or
    -not $source.report -or -not $source.runtime) {
    throw 'Source configuration must follow modules/integrated-config.example.json.'
}
if ([string]::IsNullOrWhiteSpace([string]$source.transcription.DASHSCOPE_API_KEY) -or
    [string]::IsNullOrWhiteSpace([string]$source.transcription.OSS_ACCESS_KEY_ID) -or
    [string]::IsNullOrWhiteSpace([string]$source.transcription.OSS_ACCESS_KEY_SECRET) -or
    [string]::IsNullOrWhiteSpace([string]$source.transcription.OSS_BUCKET) -or
    [string]::IsNullOrWhiteSpace([string]$source.headup.DASHSCOPE_API_KEY)) {
    throw 'Source configuration does not contain complete transcription/OSS/head-up credentials.'
}

function Resolve-Executable([string]$value) {
    if ([string]::IsNullOrWhiteSpace($value)) { throw 'A required executable is empty.' }
    if ([IO.Path]::IsPathRooted($value)) { return (Resolve-Path -LiteralPath $value -ErrorAction Stop).Path }
    return (Get-Command $value -ErrorAction Stop).Source
}
function Resolve-ProjectFile([string]$value) {
    if ([string]::IsNullOrWhiteSpace($value)) { throw 'A required media tool path is empty.' }
    if ([IO.Path]::IsPathRooted($value)) { return (Resolve-Path -LiteralPath $value -ErrorAction Stop).Path }
    if ($value -notmatch '[/\\]') { return (Get-Command $value -ErrorAction Stop).Source }
    return (Resolve-Path -LiteralPath (Join-Path $projectRoot $value) -ErrorAction Stop).Path
}
function New-Secret([int]$bytes = 48) {
    return [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes($bytes))
}
function Copy-Section($object) {
    $result = [ordered]@{}
    foreach ($property in $object.PSObject.Properties) {
        if ($null -ne $property.Value) { $result[$property.Name] = [string]$property.Value }
    }
    return $result
}
function Copy-LocalTool([string]$sourceFile, [string]$targetDirectory) {
    $target = Join-Path $targetDirectory ([IO.Path]::GetFileName($sourceFile))
    if (-not [IO.Path]::GetFullPath($sourceFile).Equals([IO.Path]::GetFullPath($target), [StringComparison]::OrdinalIgnoreCase)) {
        Copy-Item -LiteralPath $sourceFile -Destination $target -Force
    }
    return $target
}

$ffmpegSource = Resolve-ProjectFile ([string]$source.runtime.ffmpegPath)
$ffprobeSource = Resolve-ProjectFile ([string]$source.runtime.ffprobePath)
$toolRoot = Join-Path $workRoot 'tools\ffmpeg'
New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
$ffmpegTarget = Copy-LocalTool $ffmpegSource $toolRoot
$ffprobeTarget = Copy-LocalTool $ffprobeSource $toolRoot

$transcriptionToken = [string]$source.runtime.transcriptionToken
$headupToken = [string]$source.runtime.headupToken
$mediaSigningKey = [string]$source.runtime.mediaSigningKey
if ([string]::IsNullOrWhiteSpace($transcriptionToken)) { $transcriptionToken = New-Secret }
if ([string]::IsNullOrWhiteSpace($headupToken)) { $headupToken = New-Secret }
if ([string]::IsNullOrWhiteSpace($mediaSigningKey)) { $mediaSigningKey = New-Secret 64 }

$config = [ordered]@{
    schemaVersion = 1
    transcription = Copy-Section $source.transcription
    headup = Copy-Section $source.headup
    report = Copy-Section $source.report
    runtime = [ordered]@{
        javaPath = Resolve-Executable ([string]$source.runtime.javaPath)
        pythonPath = Resolve-Executable ([string]$source.runtime.pythonPath)
        ffmpegPath = $ffmpegTarget
        ffprobePath = $ffprobeTarget
        transcriptionToken = $transcriptionToken
        headupToken = $headupToken
        mediaSigningKey = $mediaSigningKey
    }
}
New-Item -ItemType Directory -Force -Path $workRoot | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText($targetPath, ($config | ConvertTo-Json -Depth 8), $utf8)
Write-Output 'Integrated classroom analysis configuration initialized inside this project; credentials were not printed.'
