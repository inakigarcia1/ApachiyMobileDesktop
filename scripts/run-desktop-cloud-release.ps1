# Release desktop + cloud backend (local.properties only).
#
# Usage:
#   .\scripts\run-desktop-cloud-release.ps1
#   .\scripts\run-desktop-cloud-release.ps1 --args="--rerun-tasks"

param([string[]]$Args)

. (Join-Path $PSScriptRoot "Desktop-RunCommon.ps1")
Start-ApachiyDesktop -Backend Cloud -BuildType Release -GradleArgs $Args
