<#
.SYNOPSIS
  Manage the Vaani laptop agent running in the background.

.EXAMPLE
  .\scripts\vaani.ps1 start      # start the background supervisor (and agent)
  .\scripts\vaani.ps1 stop       # stop both
  .\scripts\vaani.ps1 restart
  .\scripts\vaani.ps1 status     # processes, addresses, health, recent log lines
  .\scripts\vaani.ps1 logs       # follow the agent log
  .\scripts\vaani.ps1 enable-autostart    # start at Windows login (current user only)
  .\scripts\vaani.ps1 disable-autostart
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('start', 'stop', 'restart', 'status', 'logs', 'enable-autostart', 'disable-autostart')]
    [string]$Command
)

$ErrorActionPreference = 'Stop'
$AgentDir = Split-Path -Parent $PSScriptRoot
$PythonW = Join-Path $AgentDir '.venv\Scripts\pythonw.exe'
$LogDir = Join-Path $env:LOCALAPPDATA 'Vaani\logs'
$Shortcut = Join-Path ([Environment]::GetFolderPath('Startup')) 'Vaani agent.lnk'

function Get-VaaniProcesses {
    Get-CimInstance Win32_Process -Filter "Name='python.exe' OR Name='pythonw.exe'" |
        Where-Object { $_.CommandLine -match '-m app\.supervisor' -or $_.CommandLine -match '-m app\s*$' }
}

function Start-Vaani {
    if (Get-VaaniProcesses | Where-Object { $_.CommandLine -match 'app\.supervisor' }) {
        Write-Output 'Vaani is already running.'
        return
    }
    if (Get-VaaniProcesses) {
        Write-Output 'Stopping a manually started agent first...'
        Stop-Vaani
    }
    Start-Process -FilePath $PythonW -ArgumentList '-m', 'app.supervisor' -WorkingDirectory $AgentDir
    Write-Output "Vaani started in the background. Logs: $LogDir"
}

function Stop-Vaani {
    # Supervisor first, so it does not restart the agent.
    $procs = @(Get-VaaniProcesses)
    $procs | Where-Object { $_.CommandLine -match 'app\.supervisor' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 500
    Get-VaaniProcesses | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    if ($procs.Count) { Write-Output 'Vaani stopped.' } else { Write-Output 'Vaani was not running.' }
}

function Show-Status {
    $procs = @(Get-VaaniProcesses)
    $sup = $procs | Where-Object { $_.CommandLine -match 'app\.supervisor' }
    $agent = $procs | Where-Object { $_.CommandLine -notmatch 'app\.supervisor' }
    Write-Output ("Supervisor : " + $(if ($sup) { "running (pid $(@($sup)[0].ProcessId))" } else { 'not running' }))
    Write-Output ("Agent      : " + $(if ($agent) { "running (pid $(@($agent)[0].ProcessId))" } else { 'not running' }))
    Write-Output ("Autostart  : " + $(if (Test-Path $Shortcut) { 'enabled' } else { 'disabled' }))
    try {
        $h = Invoke-RestMethod -Uri 'http://127.0.0.1:8765/health' -TimeoutSec 3
        Write-Output "Health     : $($h.status)"
    } catch {
        Write-Output 'Health     : not answering on 127.0.0.1:8765'
    }
    $sl = Join-Path $LogDir 'supervisor.log'
    if (Test-Path $sl) {
        Write-Output ''
        Write-Output 'Recent supervisor log:'
        Get-Content $sl -Tail 8
    }
}

switch ($Command) {
    'start' { Start-Vaani }
    'stop' { Stop-Vaani }
    'restart' { Stop-Vaani; Start-Sleep 1; Start-Vaani }
    'status' { Show-Status }
    'logs' { Get-Content (Join-Path $LogDir 'agent.log') -Tail 30 -Wait }
    'enable-autostart' {
        $shell = New-Object -ComObject WScript.Shell
        $lnk = $shell.CreateShortcut($Shortcut)
        $lnk.TargetPath = $PythonW
        $lnk.Arguments = '-m app.supervisor'
        $lnk.WorkingDirectory = $AgentDir
        $lnk.Description = 'Vaani laptop agent (background)'
        $lnk.WindowStyle = 7
        $lnk.Save()
        Write-Output "Autostart enabled: $Shortcut"
    }
    'disable-autostart' {
        if (Test-Path $Shortcut) { Remove-Item $Shortcut; Write-Output 'Autostart disabled.' }
        else { Write-Output 'Autostart was not enabled.' }
    }
}
