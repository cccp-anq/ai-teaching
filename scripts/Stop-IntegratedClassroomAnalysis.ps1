[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$managed = @(
    @{ Name = 'backend'; Pid = '.work/app-backend.pid'; ProcessName = 'java' },
    @{ Name = 'headup'; Pid = '.work/integrated-headup.pid'; ProcessName = 'python' },
    @{ Name = 'transcription'; Pid = '.work/integrated-transcription.pid'; ProcessName = 'java' }
)
foreach ($item in $managed) {
    $pidPath = Join-Path $projectRoot $item.Pid
    if (-not (Test-Path -LiteralPath $pidPath)) { continue }
    $managedPid = 0
    if (-not [int]::TryParse((Get-Content -LiteralPath $pidPath -Raw).Trim(), [ref]$managedPid)) {
        throw "Invalid managed PID file: $pidPath"
    }
    $process = Get-Process -Id $managedPid -ErrorAction SilentlyContinue
    if ($process) {
        if ($process.ProcessName -ne $item.ProcessName -or
            $process.StartTime -lt (Get-Item -LiteralPath $pidPath).LastWriteTime.AddMinutes(-5)) {
            throw "PID $managedPid no longer belongs to managed $($item.Name); it was not stopped."
        }
        Stop-Process -Id $managedPid -Force
        Wait-Process -Id $managedPid -Timeout 15 -ErrorAction SilentlyContinue
        Write-Output "Stopped integrated $($item.Name) process $managedPid."
    }
    Remove-Item -LiteralPath $pidPath -Force
}
