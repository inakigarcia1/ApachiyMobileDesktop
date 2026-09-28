# Builds and installs the Android fullDebug APK against the cloud backend in local.properties.
# Does not read local.dev.properties.
#
# Usage:
#   .\scripts\run-android-cloud.ps1
#   .\scripts\run-android-cloud.ps1 --args="--rerun-tasks"

param(
    [string[]]$Args
)

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$LocalProps = Join-Path $Root "local.properties"
$Gradlew = Join-Path $Root "gradlew.bat"

if (-not (Test-Path $LocalProps)) {
    Write-Error "Missing $LocalProps"
}

Remove-Item Env:APACHIY_USE_LOCAL_DEV -ErrorAction SilentlyContinue

. (Join-Path $PSScriptRoot "Use-EmulatorOnlyAdb.ps1")

Write-Host "Installing Android (emulator) with local.properties (cloud)."

& $Gradlew ":androidApp:installFullDebug" "-Pnuvio.android.distribution=full" "-Pnuvio.useLocalDev=false" @Args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
