<#
.SYNOPSIS
  把工作区根目录的 .env 载入当前 PowerShell 会话的环境变量。

.DESCRIPTION
  Spring Boot 不会自动读取 .env 文件，所以需要显式载入。
  载入后，后续在同一条命令里执行 mvn / java 就能读到 TENCENT_SECRET_ID 等变量。

  设计取舍：
  - 只支持 KEY=VALUE 与 KEY="VALUE" 两种形式，遇到无法解析的行会明确报错，
    而不是悄悄忽略（静默忽略会让人以为是凭证填错了）。
  - 默认<b>不</b>打印变量值，只打印变量名，避免 SecretKey 进终端历史或日志。

.EXAMPLE
  # 载入后启动服务
  . .\scripts\Use-Env.ps1; mvn -s .mvn/settings-sandbox.xml spring-boot:run

.EXAMPLE
  # 只看载入了哪些变量
  .\scripts\Use-Env.ps1 -ShowNames
#>
[CmdletBinding()]
param(
    # .env 文件路径，默认工作区根目录
    [string] $EnvFile = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env'),

    # 是否只显示已载入的变量名（不显示值）
    [switch] $ShowNames
)

$ErrorActionPreference = 'Stop'

$envFileExists = Test-Path -LiteralPath $EnvFile
if (-not $envFileExists) {
    Write-Host "[Use-Env] 找不到 $EnvFile" -ForegroundColor Yellow
    Write-Host "[Use-Env] 请先复制 .env.example 为 .env 并填入所需配置：" -ForegroundColor Yellow
    Write-Host "          Copy-Item .env.example .env" -ForegroundColor Cyan
    Write-Host "[Use-Env] 继续做会话变量自检（环境里可能已经有值）。" -ForegroundColor Gray
}

$loaded = @()
$lineNumber = 0

if ($envFileExists) {
foreach ($line in Get-Content -LiteralPath $EnvFile -Encoding UTF8) {
    $lineNumber++
    $trimmed = $line.Trim()

    # 跳过空行与注释
    if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }

    # 只接受 KEY=VALUE，KEY 由字母、数字、下划线组成
    if ($trimmed -notmatch '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
        Write-Warning "[Use-Env] 第 $lineNumber 行无法解析，已跳过：$trimmed"
        continue
    }

    $name = $Matches[1]
    $value = $Matches[2].Trim()

    # 去掉成对的引号
    if ($value.Length -ge 2) {
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
    }

    # 空值也要显式设置：否则同一个会话里换 .env 时，
    # 上一份 .env 的值会残留在环境变量里，造成"两份配置混在一起"的诡异现象。
    # 空值表示"未配置"，应用侧会给出对应的待办提示。
    Set-Item -Path "Env:$name" -Value $value
    $loaded += $name
}
}

# 只打印变量名，绝不打印值：SecretKey 不应进终端历史或日志
if ($envFileExists) {
    Write-Host "[Use-Env] 已从 $EnvFile 载入 $($loaded.Count) 个变量：" -ForegroundColor Green
    if ($ShowNames) {
        foreach ($name in $loaded) { Write-Host "  - $name" -ForegroundColor Gray }
    }
}

# 关键项自检：按"当前生效的提供方"检查，只报是否配置、不打印任何值。
# 之前这里写死检查 TENCENT_SECRET_ID/KEY，结果选了 TokenHub 也会被提示去填腾讯云凭证——
# 那是会把人带偏的错误信息。
#
# 默认值必须与 application.yml 的 ${ASR_PROVIDER:wand} 保持一致。
# 这里曾写成 'tencent'，于是在没设 ASR_PROVIDER 时（也就是真实默认情况下），
# 脚本会去检查一份根本不会被使用的腾讯云凭证。两处默认值不一致本身就是 bug。
$provider = if ($env:ASR_PROVIDER) { $env:ASR_PROVIDER.Trim().ToLower() } else { 'wand' }

function Test-EnvSet([string] $name) {
    $item = Get-Item "Env:$name" -ErrorAction SilentlyContinue
    return $null -ne $item -and -not [string]::IsNullOrWhiteSpace($item.Value)
}

switch ($provider) {
    'wand' {
        if (-not (Test-EnvSet 'WAND_API_KEY')) {
            Write-Host '[Use-Env] 注意：ASR_PROVIDER=wand 但 WAND_API_KEY 未配置，提交任务会失败（PROCESSING_FAILED）。' -ForegroundColor Yellow
            Write-Host '          另外需在控制台开通后付费，否则会返回 402（401007）：https://console.cloud.tencent.com/tokenhub/inference' -ForegroundColor Yellow
        }
    }
    { $_ -in 'aliyun', 'aliyun-filetrans' } {
        if (-not (Test-EnvSet 'DASHSCOPE_API_KEY')) {
            Write-Host '[Use-Env] 注意：ASR_PROVIDER 为阿里云但 DASHSCOPE_API_KEY 未配置，提交任务会失败。' -ForegroundColor Yellow
        }
        if (-not (Test-EnvSet 'DASHSCOPE_WORKSPACE_ID')) {
            Write-Host '[Use-Env] 注意：DASHSCOPE_WORKSPACE_ID 未配置，会回落到旧公共域名 dashscope.aliyuncs.com；' -ForegroundColor Yellow
            Write-Host '          该模型按官方说明是在业务空间专属域名下提供服务。' -ForegroundColor Yellow
        }
        # 这一条最关键：阿里云长音频档只收公网 URL（不支持 Base64/二进制流），
        # 而当前 AudioStaging 只有"明确失败"的实现。没配暂存时每个任务都会失败。
        Write-Host '[Use-Env] 注意：阿里云长音频档只接受公网 URL，必须先有音频暂存（AudioStaging）实现；' -ForegroundColor Yellow
        Write-Host '          当前仓库尚未提供，启动日志会打一行 ERROR 说明。见 docs/阿里云模型选型.md §4。' -ForegroundColor Yellow
    }
    'tencent' {
        $missing = @('TENCENT_SECRET_ID', 'TENCENT_SECRET_KEY') | Where-Object { -not (Test-EnvSet $_) }
        if ($missing.Count -gt 0) {
            Write-Host "[Use-Env] 注意：ASR_PROVIDER=tencent 但 $($missing -join ', ') 未配置，提交任务会失败。" -ForegroundColor Yellow
        }
        Write-Host '[Use-Env] 注意：tencent 提供方在契约路径下不可用（契约路径走内联音频，而它只实现了 URL 提交）。' -ForegroundColor Yellow
    }
    default {
        Write-Host "[Use-Env] 注意：ASR_PROVIDER=$provider 不是已知提供方" -ForegroundColor Yellow
        Write-Host '          （可选：wand = TokenHub，aliyun-filetrans = 阿里云百炼，tencent = 腾讯云标准 ASR），服务启动时会报错。' -ForegroundColor Yellow
    }
}
