param(
  [switch]$NoInfra,
  [switch]$SkipMinerU,
  [switch]$RequireMinerU,
  [string]$BackendProfile = "docker"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
. (Join-Path $Root "scripts\lib\env.ps1") -Root $Root

if (-not $NoInfra) {
  $infraArgs = @()
  if ($SkipMinerU) {
    $infraArgs += "-SkipMinerU"
  }
  if ($RequireMinerU) {
    $infraArgs += "-RequireMinerU"
  }
  & (Join-Path $Root "scripts\start-infra.ps1") @infraArgs
}

$pwsh = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
if (-not $pwsh) {
  $pwsh = (Get-Command powershell -ErrorAction Stop).Source
}

Start-Process $pwsh -ArgumentList @(
  "-NoExit",
  "-ExecutionPolicy", "Bypass",
  "-File", (Join-Path $Root "scripts\start-backend.ps1"),
  "-Profile", $BackendProfile
) -WorkingDirectory $Root

Start-Process $pwsh -ArgumentList @(
  "-NoExit",
  "-ExecutionPolicy", "Bypass",
  "-File", (Join-Path $Root "scripts\start-frontend.ps1")
) -WorkingDirectory $Root

Write-Host "NexusMind startup launched." -ForegroundColor Green
Write-Host "Backend:  http://localhost:$env:BACKEND_PORT" -ForegroundColor Green
Write-Host "Frontend: check the Vite URL printed in the frontend window, usually http://localhost:9527 or http://localhost:5173" -ForegroundColor Green
