param(
  [switch]$SkipInit,
  [switch]$SkipMinerU,
  [switch]$RequireMinerU
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
. (Join-Path $Root "scripts\lib\env.ps1") -Root $Root

$composeDir = Join-Path $Root "backend\docs"

Write-Host "Starting NexusMind core infrastructure..." -ForegroundColor Cyan
& docker compose -f (Join-Path $composeDir "docker-compose.yaml") up -d
if ($LASTEXITCODE -ne 0) {
  throw "docker compose failed with exit code $LASTEXITCODE."
}

if ($SkipInit) {
  return
}

Write-Host "Waiting for MySQL..." -ForegroundColor Cyan
$mysqlReady = $false
for ($i = 1; $i -le 30; $i++) {
  & docker exec -e "MYSQL_PWD=$env:MYSQL_ROOT_PASSWORD" mysql mysqladmin ping -uroot --silent 2>$null | Out-Null
  if ($LASTEXITCODE -eq 0) {
    $mysqlReady = $true
    break
  }
  Start-Sleep -Seconds 2
}

if (-not $mysqlReady) {
  throw "MySQL container did not become ready in time."
}

& docker exec -e "MYSQL_PWD=$env:MYSQL_ROOT_PASSWORD" mysql mysql -uroot -e "CREATE DATABASE IF NOT EXISTS nexusmind DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
  throw "Failed to create or verify database nexusmind."
}
Write-Host "Database nexusmind is ready." -ForegroundColor Green

Write-Host "Preparing MinIO bucket uploads..." -ForegroundColor Cyan
& docker exec minio sh -c "mc alias set local http://127.0.0.1:19000 '$env:MINIO_ACCESS_KEY' '$env:MINIO_SECRET_KEY' >/dev/null 2>&1 && mc mb -p local/uploads >/dev/null 2>&1 || true" 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
  Write-Warning "MinIO bucket initialization did not complete. You may need to create bucket 'uploads' manually."
}

if (-not $SkipMinerU) {
  Write-Host "Starting MinerU service..." -ForegroundColor Cyan
  & docker compose -f (Join-Path $composeDir "docker-compose.yaml") --profile mineru up -d mineru-api
  if ($LASTEXITCODE -ne 0) {
    $message = "MinerU service did not start. Tika parsing and the rest of NexusMind can still run; choose MinerU only after mineru-api is ready."
    if ($RequireMinerU) {
      throw $message
    }
    Write-Warning $message
  }
}

Write-Host "Infrastructure startup requested. Check with: docker ps" -ForegroundColor Green
