<#
.SYNOPSIS
  包装 mvn 调用：固定使用沙盒专用 settings 与工作区内的本地仓库。

.DESCRIPTION
  本机默认本地仓库位于 D:\develop\apache-maven-3.9.4\mvn_repo，在 DSH 文件沙盒
  （workspace-write）之外，会被拒绝写入。本脚本通过 .mvn/settings-sandbox.xml
  把本地仓库指向 .data/m2repo，避免每次都要申请更宽的文件权限。

  另外注意：不要把 -Dmaven.repo.local=D:/... 直接写在命令行里，
  PowerShell 会在冒号处拆参，导致 Maven 报 "No plugin found for prefix '.repo.local=D'"。

.EXAMPLE
  .\scripts\Mvn.ps1 compile
  .\scripts\Mvn.ps1 test
  .\scripts\Mvn.ps1 spring-boot:run
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $MavenArgs = @('test')
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot

# 顺带载入 .env，这样 spring-boot:run 也能拿到凭证
& (Join-Path $PSScriptRoot 'Use-Env.ps1')

Write-Host "[Mvn] mvn $($MavenArgs -join ' ')" -ForegroundColor Cyan

Push-Location $repoRoot
try {
    & mvn -B -s (Join-Path $repoRoot '.mvn\settings-sandbox.xml') @MavenArgs
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
