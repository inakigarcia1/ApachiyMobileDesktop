# Release desktop + local backend (local.dev.properties). Packaged-like run (no operator overlay).
#
# Usage:
#   .\scripts\run-desktop-local-release.ps1
#   .\scripts\run-desktop-local-release.ps1 --args="--rerun-tasks"

param([string[]]$Args)

. (Join-Path $PSScriptRoot "Desktop-RunCommon.ps1")
Start-ApachiyDesktop -Backend Local -BuildType Release -GradleArgs $Args
