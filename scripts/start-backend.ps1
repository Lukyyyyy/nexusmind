param(
  [string]$Profile = "docker"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
. (Join-Path $Root "scripts\lib\env.ps1") -Root $Root

function Get-WindowsExcludedTcpPortRange {
  param([int]$Port)

  $ranges = netsh interface ipv4 show excludedportrange protocol=tcp 2>$null
  foreach ($line in $ranges) {
    if ($line -match "^\s*(\d+)\s+(\d+)") {
      $startPort = [int]$Matches[1]
      $endPort = [int]$Matches[2]
      if ($Port -ge $startPort -and $Port -le $endPort) {
        return "$startPort-$endPort"
      }
    }
  }

  return $null
}

$excludedRange = Get-WindowsExcludedTcpPortRange -Port ([int]$env:BACKEND_PORT)
if ($excludedRange) {
  Write-Host "Backend port $env:BACKEND_PORT is reserved by Windows excluded TCP port range $excludedRange." -ForegroundColor Red
  Write-Host "Set BACKEND_PORT in .env.local to a port outside the excluded ranges, for example 18081." -ForegroundColor Yellow
  Write-Host "You can inspect ranges with: netsh interface ipv4 show excludedportrange protocol=tcp" -ForegroundColor Yellow
  exit 1
}

$portOwner = Get-NetTCPConnection -LocalPort ([int]$env:BACKEND_PORT) -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($portOwner) {
  $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($portOwner.OwningProcess)" -ErrorAction SilentlyContinue
  Write-Host "Backend port $env:BACKEND_PORT is already in use." -ForegroundColor Red
  if ($process) {
    Write-Host "PID: $($process.ProcessId)" -ForegroundColor Yellow
    Write-Host "Process: $($process.Name)" -ForegroundColor Yellow
    Write-Host "CommandLine: $($process.CommandLine)" -ForegroundColor Yellow
  } else {
    Write-Host "PID: $($portOwner.OwningProcess)" -ForegroundColor Yellow
  }
  Write-Host "Set BACKEND_PORT in .env.local to another value, for example 18081, or stop the owning process." -ForegroundColor Yellow
  exit 1
}

Set-Location (Join-Path $Root "backend")
Write-Host "Starting NexusMind backend on profile '$Profile'..." -ForegroundColor Cyan
mvn spring-boot:run "-Dspring-boot.run.profiles=$Profile" "-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
