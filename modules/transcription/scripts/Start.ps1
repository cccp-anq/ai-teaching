<#
.SYNOPSIS
  启动转写模块（前台运行，Ctrl+C 停止）。

.DESCRIPTION
  会先载入 .env，再用 spring-boot:run 启动。
  启动日志会打印契约身份与能力边界，可据此确认：
  providerId/moduleVersion、识别引擎、媒体工具链（ffmpeg）是否可用。

.EXAMPLE
  .\scripts\Start.ps1
  .\scripts\Start.ps1 -Port 9090
#>
[CmdletBinding()]
param(
    # 覆盖服务端口；不传则用 .env 的 SERVER_PORT，再默认 8080
    [int] $Port = 0,

    # 关闭轮询兜底（联调时想手动触发 reconcile 可以这样用）
    [switch] $DisablePoll
)

$ErrorActionPreference = 'Stop'

& (Join-Path $PSScriptRoot 'Use-Env.ps1')

$repoRoot = Split-Path -Parent $PSScriptRoot
if ($Port -gt 0) { $env:SERVER_PORT = "$Port" }
if ($DisablePoll) { $env:ASR_POLL_ENABLED = 'false' }

Write-Host "[Start] 正在启动 ASR 沙盒服务（端口 $(if ($env:SERVER_PORT) { $env:SERVER_PORT } else { '8080' })）..." -ForegroundColor Cyan
Write-Host '[Start] 停止：Ctrl+C' -ForegroundColor Gray

Push-Location $repoRoot
try {
    & mvn -B -s (Join-Path $repoRoot '.mvn\settings-sandbox.xml') spring-boot:run
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
