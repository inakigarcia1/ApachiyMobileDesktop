# Alias for run-android-local-debug.ps1

param([string[]]$Args)

& (Join-Path $PSScriptRoot "run-android-local-debug.ps1") @Args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
