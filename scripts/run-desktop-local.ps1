# Runs Apachiy Desktop against the local backend stack (docker compose, etc.)
# without changing cloud URLs in local.properties.
#
# Usage:
#   .\scripts\run-desktop-local.ps1
#   .\scripts\run-desktop-local.ps1 --args="--rerun-tasks"

param(
    [string[]]$Args
)

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$DevProps = Join-Path $Root "local.dev.properties"
$DevExample = Join-Path $Root "local.dev.example.properties"
$Gradlew = Join-Path $Root "gradlew.bat"

if (-not (Test-Path $DevProps)) {
    if (-not (Test-Path $DevExample)) {
        Write-Error "Missing $DevExample"
    }
    Copy-Item $DevExample $DevProps
    Write-Host "Created local.dev.properties from template. Edit backend URLs/keys if needed."
}

$env:APACHIY_USE_LOCAL_DEV = "1"

Write-Host "Starting desktop with local.dev.properties overlay (cloud local.properties unchanged)."

& $Gradlew ":composeApp:run" '-Pnuvio.useLocalDev=true' @Args
