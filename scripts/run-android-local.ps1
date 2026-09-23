# Builds and installs the Android fullDebug APK against the local backend.
# local.dev.properties should keep localhost; Gradle maps it to 10.0.2.2 for the emulator.
#
# Usage:
#   .\scripts\run-android-local.ps1
#   .\scripts\run-android-local.ps1 --args="--rerun-tasks"

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
    Write-Host "Created local.dev.properties from template. Set APACHIY_SUPABASE_ANON_KEY for your local stack."
}

$env:APACHIY_USE_LOCAL_DEV = "1"

Write-Host "Installing Android (emulator) with local.dev.properties (localhost -> 10.0.2.2 in APK)."

& $Gradlew ":androidApp:installFullDebug" '-Pnuvio.android.distribution=full' '-Pnuvio.useLocalDev=true' @Args
