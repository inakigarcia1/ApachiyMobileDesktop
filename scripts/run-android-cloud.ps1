# Alias for run-android-cloud-debug.ps1

param([string[]]$Args)

& (Join-Path $PSScriptRoot "run-android-cloud-debug.ps1") @Args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
