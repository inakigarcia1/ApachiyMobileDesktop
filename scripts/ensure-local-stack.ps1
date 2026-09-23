# Ensure local Supabase (Kong :8000) and Apachiy API (:10050) are running.
# Does not tear anything down. Safe to re-run.

$ErrorActionPreference = "Stop"

$DesktopRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$ReposRoot = Resolve-Path (Join-Path $DesktopRoot "..")
$SupabaseDir = Join-Path $ReposRoot "Apachiy-NuvioFork\infra\supabase"
$ApiDir = Join-Path $ReposRoot "Apachiy"

function Test-TcpPort([int]$Port) {
    try {
        $client = [System.Net.Sockets.TcpClient]::new()
        $async = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        $ok = $async.AsyncWaitHandle.WaitOne(800)
        if (-not $ok) { $client.Close(); return $false }
        $client.EndConnect($async)
        $client.Close()
        return $true
    } catch {
        return $false
    }
}

function Assert-Docker {
    docker info --format "{{.ServerVersion}}" 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker is not running. Start Docker Desktop and retry."
    }
}

Assert-Docker

$supabaseUp = Test-TcpPort 8000
$apiUp = Test-TcpPort 10050

if (-not $supabaseUp) {
    $envFile = Join-Path $SupabaseDir ".env"
    if (-not (Test-Path $envFile)) {
        throw "Missing $envFile. Copy .env.example and fill POSTGRES_PASSWORD / JWT keys."
    }
    Write-Host "Starting Supabase stack in $SupabaseDir"
    Push-Location $SupabaseDir
    try {
        docker compose --env-file .env -f docker-compose.yml up -d
        if ($LASTEXITCODE -ne 0) { throw "docker compose up failed for Supabase" }
    } finally {
        Pop-Location
    }
    $deadline = (Get-Date).AddMinutes(4)
    while (-not (Test-TcpPort 8000)) {
        if ((Get-Date) -gt $deadline) { throw "Supabase Kong did not open port 8000 in time." }
        Start-Sleep -Seconds 3
    }
    Write-Host "Supabase Kong is up on port 8000"
} else {
    Write-Host "Supabase already listening on port 8000"
}

if (-not $apiUp) {
    if (-not (Test-Path (Join-Path $ApiDir "docker-compose.yml"))) {
        throw "Missing docker-compose.yml in $ApiDir"
    }
    Write-Host "Starting Apachiy API stack in $ApiDir"
    Push-Location $ApiDir
    try {
        docker compose up -d apachiy-api community-subs
        if ($LASTEXITCODE -ne 0) { throw "docker compose up failed for Apachiy API" }
    } finally {
        Pop-Location
    }
    $deadline = (Get-Date).AddMinutes(8)
    while (-not (Test-TcpPort 10050)) {
        if ((Get-Date) -gt $deadline) { throw "Apachiy API did not open port 10050 in time." }
        Start-Sleep -Seconds 4
    }
    Write-Host "Apachiy API is up on port 10050"
} else {
    Write-Host "Apachiy API already listening on port 10050"
}

Write-Host ""
Write-Host "DB access (full postgres, no password on host):"
Write-Host '  docker exec -it apachiy-supabase-db psql -U supabase_admin -d postgres'
Write-Host "Host port: localhost:54322"
