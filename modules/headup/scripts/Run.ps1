# 跑测试 / 跑 CLI：统一设置 PYTHONPATH（src + tests），避免每个命令手动设
# 用法：
#   .\scripts\Run.ps1 -Target test          # 全部测试
#   .\scripts\Run.ps1 -Target selftest      # 内置自检（无网络、无模型）
#   .\scripts\Run.ps1 -Target serve         # 启动服务
#   .\scripts\Run.ps1 -Target verify        # 校验交付 fixture
#   .\scripts\Run.ps1 -Target gen           # 重新生成交付 fixture（需要 ffmpeg）
#   .\scripts\Run.ps1 -Target cli -CliArgs @('run','--file','课堂.mp4')
#   .\scripts\Run.ps1 -Target frame -CliArgs @('--file','课堂.mp4','--at','600000','--out','.work\f.jpg')
# 加 -WithEnv 会先载入 .env
param(
    [ValidateSet('test', 'selftest', 'serve', 'verify', 'gen', 'cli', 'frame')]
    [string]$Target = 'test',
    [string[]]$CliArgs = @(),
    [switch]$WithEnv
)

$repo = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $repo
$env:PYTHONPATH = "$repo\src;$repo\tests"
$env:PYTHONIOENCODING = 'utf-8'

if ($WithEnv) { . (Join-Path $PSScriptRoot 'Use-Env.ps1') }

# 便携 ffmpeg（仓库同级 videoreact 里有一份）；已在 PATH 或已显式配置时不覆盖
if (-not $env:FFMPEG_PATH) {
    $portable = Join-Path $repo '..\videoreact\.work\tools\ffmpeg\bin\ffmpeg.exe'
    if (Test-Path $portable) {
        $env:FFMPEG_PATH = (Resolve-Path $portable).Path
        $env:FFPROBE_PATH = (Resolve-Path ($portable -replace 'ffmpeg\.exe$', 'ffprobe.exe')).Path
    }
}

switch ($Target) {
    'test'     { python -m unittest discover -s tests -t . }
    'selftest' { python -m headup selftest }
    'serve'    { python -m headup serve }
    'verify'   { python -m headup verify-fixtures docs/fixtures/head-up-module-v0.1 }
    'gen'      { python -m headup generate-fixtures --output docs/fixtures/head-up-module-v0.1 `
                    --media docs/fixtures/head-up-module-v0.1/media/headup-synthetic-classroom-20s.mp4 `
                    --duration-ms 20000 }
    'frame'    { python -m headup extract-frame @CliArgs }
    'cli'      { python -m headup @CliArgs }
}
