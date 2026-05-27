param(
  [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
)

try {
  $utf8NoBom = New-Object System.Text.UTF8Encoding $false
  [Console]::InputEncoding = $utf8NoBom
  [Console]::OutputEncoding = $utf8NoBom
  $OutputEncoding = $utf8NoBom
  if ($env:OS -eq "Windows_NT") {
    chcp 65001 > $null
  }
} catch {
  Write-Warning "Failed to set console encoding to UTF-8: $($_.Exception.Message)"
}

function Import-DotEnvFile {
  param([string]$Path)

  if (-not (Test-Path -LiteralPath $Path)) {
    return
  }

  Get-Content -LiteralPath $Path | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith("#")) {
      return
    }

    $parts = $line -split "=", 2
    if ($parts.Count -ne 2) {
      return
    }

    $name = $parts[0].Trim()
    $value = $parts[1].Trim().Trim('"').Trim("'")
    if ($name) {
      [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
  }
}

function Set-DefaultEnv {
  param(
    [string]$Name,
    [string]$Value
  )

  if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name, "Process"))) {
    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
  }
}

Import-DotEnvFile -Path (Join-Path $Root ".env.local")

Set-DefaultEnv "MYSQL_ROOT_PASSWORD" "nexusmind-local"
Set-DefaultEnv "MYSQL_PASSWORD" $env:MYSQL_ROOT_PASSWORD
Set-DefaultEnv "MYSQL_HOST_PORT" "13306"
Set-DefaultEnv "MYSQL_PORT" $env:MYSQL_HOST_PORT
Set-DefaultEnv "REDIS_PASSWORD" "nexusmind-local"
Set-DefaultEnv "REDIS_HOST_PORT" "6379"
Set-DefaultEnv "REDIS_PORT" $env:REDIS_HOST_PORT
Set-DefaultEnv "ELASTICSEARCH_PASSWORD" "nexusmind-local"
Set-DefaultEnv "ELASTICSEARCH_HOST_PORT" "9200"
Set-DefaultEnv "ELASTICSEARCH_PORT" $env:ELASTICSEARCH_HOST_PORT
Set-DefaultEnv "MINIO_ROOT_USER" "admin"
Set-DefaultEnv "MINIO_ROOT_PASSWORD" "nexusmind-local"
Set-DefaultEnv "MINIO_ACCESS_KEY" $env:MINIO_ROOT_USER
Set-DefaultEnv "MINIO_SECRET_KEY" $env:MINIO_ROOT_PASSWORD
Set-DefaultEnv "MINIO_API_HOST_PORT" "19000"
Set-DefaultEnv "MINIO_CONSOLE_HOST_PORT" "19001"
Set-DefaultEnv "MINIO_API_PORT" $env:MINIO_API_HOST_PORT
Set-DefaultEnv "KAFKA_HOST_PORT" "9092"
Set-DefaultEnv "KAFKA_CONTROLLER_HOST_PORT" "9093"
Set-DefaultEnv "KAFKA_PORT" $env:KAFKA_HOST_PORT
Set-DefaultEnv "JWT_SECRET_KEY" "nexusmind-local-development-secret-key-change-before-production"
Set-DefaultEnv "BACKEND_PORT" "18081"
Set-DefaultEnv "ADMIN_USERNAME" "admin"
Set-DefaultEnv "ADMIN_PASSWORD" $env:MYSQL_PASSWORD
Set-DefaultEnv "VITE_SERVICE_BASE_URL" "http://localhost:$env:BACKEND_PORT/api/v1"
Set-DefaultEnv "VITE_OTHER_SERVICE_BASE_URL" "{`"api`":`"http://localhost:$env:BACKEND_PORT/api/v1`",`"ws`":`"ws://localhost:$env:BACKEND_PORT`"}"
Set-DefaultEnv "LANGFUSE_TRACING_ENABLED" "false"
Set-DefaultEnv "LANGFUSE_PUBLIC_KEY" ""
Set-DefaultEnv "LANGFUSE_SECRET_KEY" ""
Set-DefaultEnv "DEEPSEEK_API_KEY" ""
Set-DefaultEnv "EMBEDDING_API_KEY" ""

if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY) -or [string]::IsNullOrWhiteSpace($env:EMBEDDING_API_KEY)) {
  Write-Warning "DEEPSEEK_API_KEY or EMBEDDING_API_KEY is not configured. Basic features can start, but AI chat and vectorization will not work."
}
