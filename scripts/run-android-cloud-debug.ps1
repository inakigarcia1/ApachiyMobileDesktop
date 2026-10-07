# fullDebug + cloud backend. Package: com.apachiy.app.debug
#
# Usage:
#   .\scripts\run-android-cloud-debug.ps1
#   .\scripts\run-android-cloud-debug.ps1 --args="--rerun-tasks"

param([string[]]$Args)

. (Join-Path $PSScriptRoot "Android-InstallCommon.ps1")
Install-ApachiyAndroid -Backend Cloud -BuildType Debug -GradleArgs $Args
