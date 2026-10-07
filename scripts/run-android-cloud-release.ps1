# fullRelease + cloud backend. Package: com.apachiy.app (needs release keystore in local.properties)
#
# Usage:
#   .\scripts\run-android-cloud-release.ps1
#   .\scripts\run-android-cloud-release.ps1 --args="--rerun-tasks"

param([string[]]$Args)

. (Join-Path $PSScriptRoot "Android-InstallCommon.ps1")
Install-ApachiyAndroid -Backend Cloud -BuildType Release -GradleArgs $Args
