# fullDebug + local backend. Package: com.apachiy.app.debug
#
# Usage:
#   .\scripts\run-android-local-debug.ps1
#   .\scripts\run-android-local-debug.ps1 --args="--rerun-tasks"

param([string[]]$Args)

. (Join-Path $PSScriptRoot "Android-InstallCommon.ps1")
Install-ApachiyAndroid -Backend Local -BuildType Debug -GradleArgs $Args
