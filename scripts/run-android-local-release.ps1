# fullRelease + local backend. Package: com.apachiy.app (needs release keystore in local.properties)
#
# Usage:
#   .\scripts\run-android-local-release.ps1
#   .\scripts\run-android-local-release.ps1 --args="--rerun-tasks"

param([string[]]$Args)

. (Join-Path $PSScriptRoot "Android-InstallCommon.ps1")
Install-ApachiyAndroid -Backend Local -BuildType Release -GradleArgs $Args
