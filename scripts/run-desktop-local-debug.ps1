# Debug desktop + local backend (local.dev.properties). Operator / dev tooling on.
#
# Usage:
#   .\scripts\run-desktop-local-debug.ps1
#   .\scripts\run-desktop-local-debug.ps1 --args="--rerun-tasks"

param([string[]]$Args)

. (Join-Path $PSScriptRoot "Desktop-RunCommon.ps1")
Start-ApachiyDesktop -Backend Local -BuildType Debug -GradleArgs $Args
