# Ensure local stack, QA accounts, and Apachiy Desktop with the agent-QA sidecar.
# Safe to re-run. Does not docker compose down.

param(
    [switch]$SkipDesktop
)

$ErrorActionPreference = "Stop"

$DesktopRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$ReposRoot = Resolve-Path (Join-Path $DesktopRoot "..")
$SupabaseDir = Join-Path $ReposRoot "Apachiy-NuvioFork\infra\supabase"
$SecretsPath = Join-Path $DesktopRoot "qa\apachiy-subsync-loop\secrets.local.json"
$QaCurrent = Join-Path $DesktopRoot "qa\runs\current"
$Heartbeat = Join-Path $QaCurrent "heartbeat.json"

function Test-DesktopAlive {
    if (-not (Test-Path $Heartbeat)) { return $false }
    try {
        $raw = [System.IO.File]::ReadAllText($Heartbeat)
        if ($raw -notmatch '"updatedAtEpochMs"\s*:\s*(\d+)') { return $false }
        $updated = [int64]$Matches[1]
        $ageMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $updated
        return $ageMs -lt 20000
    } catch {
        return $false
    }
}

Write-Host "Ensuring Docker stack..."
& (Join-Path $PSScriptRoot "ensure-local-stack.ps1")
if ($LASTEXITCODE -ne 0) { throw "ensure-local-stack.ps1 failed" }

Write-Host "Ensuring QA users are active with a default profile..."
@'
UPDATE apachiy."Users"
SET "IsActive" = TRUE,
    "ExpirationDate" = DATE '2027-12-31'
WHERE "EmailNormalized" IN ('q@q', 'a@a', 'test1@gmail.com');

INSERT INTO public.profiles (
    user_id, profile_index, name, avatar_color_hex,
    uses_primary_addons, uses_primary_plugins, created_at, updated_at
)
SELECT u.id, 1, 'QA', '#1E88E5', false, false, NOW(), NOW()
FROM auth.users u
WHERE lower(u.email) IN ('q@q', 'a@a', 'test1@gmail.com')
  AND NOT EXISTS (
      SELECT 1 FROM public.profiles p WHERE p.user_id = u.id
  );
'@ | docker exec -i apachiy-supabase-db psql -U supabase_admin -d postgres | Out-Host

$envPath = Join-Path $SupabaseDir ".env"
if (-not (Test-Path $envPath)) { throw "Missing $envPath" }
$anon = $null
$service = $null
Get-Content $envPath | ForEach-Object {
    if ($_ -match '^\s*ANON_KEY=(.+)$') { $anon = $Matches[1].Trim() }
    if ($_ -match '^\s*SERVICE_ROLE_KEY=(.+)$') { $service = $Matches[1].Trim() }
}
if ([string]::IsNullOrWhiteSpace($anon) -or [string]::IsNullOrWhiteSpace($service)) {
    throw "ANON_KEY / SERVICE_ROLE_KEY missing from supabase .env"
}

if (-not (Test-Path $SecretsPath)) { throw "Missing $SecretsPath" }
$secrets = Get-Content $SecretsPath -Raw | ConvertFrom-Json

function Get-AuthUserId([string]$Email) {
    $escaped = $Email.Replace("'", "''")
    $sql = "SELECT id FROM auth.users WHERE lower(email) = lower('$escaped') LIMIT 1;"
    $id = $sql | docker exec -i apachiy-supabase-db psql -U supabase_admin -d postgres -tA
    return $id.Trim()
}

function Test-Password([string]$Email, [string]$Password) {
    try {
        $body = @{ email = $Email; password = $Password } | ConvertTo-Json -Compress
        $resp = Invoke-RestMethod -Method Post `
            -Uri "http://127.0.0.1:8000/auth/v1/token?grant_type=password" `
            -Headers @{ apikey = $anon; Authorization = "Bearer $anon" } `
            -ContentType "application/json" `
            -Body $body `
            -TimeoutSec 15
        return [bool]$resp.access_token
    } catch {
        return $false
    }
}

foreach ($account in $secrets.accounts) {
    $email = [string]$account.email
    $password = [string]$account.password
    if (Test-Password $email $password) {
        Write-Host "Login OK for $email"
        continue
    }
    $userId = Get-AuthUserId $email
    if ([string]::IsNullOrWhiteSpace($userId)) {
        Write-Host "Creating auth user $email"
        $createBody = @{
            email = $email
            password = $password
            email_confirm = $true
        } | ConvertTo-Json -Compress
        Invoke-RestMethod -Method Post `
            -Uri "http://127.0.0.1:8000/auth/v1/admin/users" `
            -Headers @{ apikey = $service; Authorization = "Bearer $service" } `
            -ContentType "application/json" `
            -Body $createBody `
            -TimeoutSec 20 | Out-Null
    } else {
        Write-Host "Resetting password for $email"
        $resetBody = @{ password = $password; email_confirm = $true } | ConvertTo-Json -Compress
        Invoke-RestMethod -Method Put `
            -Uri "http://127.0.0.1:8000/auth/v1/admin/users/$userId" `
            -Headers @{ apikey = $service; Authorization = "Bearer $service" } `
            -ContentType "application/json" `
            -Body $resetBody `
            -TimeoutSec 20 | Out-Null
    }
    if (-not (Test-Password $email $password)) {
        throw "Could not verify login for $email"
    }
    Write-Host "Login OK for $email (after repair)"
}

if ($SkipDesktop) {
    Write-Host "SkipDesktop set; not launching Apachiy Desktop."
    return
}

if (Test-DesktopAlive) {
    Write-Host "Apachiy Desktop QA sidecar already alive (heartbeat < 20s)."
    return
}

Write-Host "Starting Apachiy Desktop with agent-QA sidecar (this window stays on gradle run)..."
& (Join-Path $PSScriptRoot "run-desktop-qa.ps1")
