$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
. (Join-Path $Root "scripts\lib\env.ps1") -Root $Root

Set-Location (Join-Path $Root "frontend")
if (-not (Test-Path -LiteralPath "node_modules")) {
  Write-Host "Installing frontend dependencies..." -ForegroundColor Cyan
  pnpm install --frozen-lockfile
}

Write-Host "Starting NexusMind frontend..." -ForegroundColor Cyan
pnpm dev
