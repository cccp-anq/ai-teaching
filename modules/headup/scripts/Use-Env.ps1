# 载入 .env 到当前 PowerShell 会话（本模块不会自动读 .env）
# 用法： . .\scripts\Use-Env.ps1      （注意前面的点，表示在当前会话生效）
param(
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env')
)

$resolved = Resolve-Path -LiteralPath $EnvFile -ErrorAction SilentlyContinue
if (-not $resolved) {
    Write-Host "未找到 $EnvFile，跳过（可先 Copy-Item .env.example .env）" -ForegroundColor Yellow
    return
}

$count = 0
Get-Content -LiteralPath $resolved -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }
    $index = $line.IndexOf('=')
    if ($index -lt 1) { return }
    $name = $line.Substring(0, $index).Trim()
    $value = $line.Substring($index + 1).Trim()
    Set-Item -Path "env:$name" -Value $value
    $count++
}
Write-Host "已载入 $count 个环境变量（来自 $resolved）" -ForegroundColor Green
