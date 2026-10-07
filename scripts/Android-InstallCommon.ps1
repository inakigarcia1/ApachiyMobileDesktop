# Shared install logic for run-android-*.ps1 (emulator-only adb unless APACHIY_ALLOW_ANY_ADB_DEVICE=1).

function Install-ApachiyAndroid {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet("Local", "Cloud")]
        [string]$Backend,

        [Parameter(Mandatory = $true)]
        [ValidateSet("Debug", "Release")]
        [string]$BuildType,

        [string[]]$GradleArgs
    )

    $ErrorActionPreference = "Stop"

    $Root = Resolve-Path (Join-Path $PSScriptRoot "..")
    $Gradlew = Join-Path $Root "gradlew.bat"
    $DevProps = Join-Path $Root "local.dev.properties"
    $DevExample = Join-Path $Root "local.dev.example.properties"
    $LocalProps = Join-Path $Root "local.properties"

    $installTask = if ($BuildType -eq "Debug") { "installFullDebug" } else { "installFullRelease" }
    $appId = if ($BuildType -eq "Debug") { "com.apachiy.app.debug" } else { "com.apachiy.app" }

    if ($Backend -eq "Local") {
        if (-not (Test-Path $DevProps)) {
            if (-not (Test-Path $DevExample)) {
                throw "Missing $DevExample"
            }
            Copy-Item $DevExample $DevProps
            Write-Host "Created local.dev.properties from template. Set APACHIY_SUPABASE_ANON_KEY for your local stack."
        }
        $env:APACHIY_USE_LOCAL_DEV = "1"
        $useLocalDevFlag = "true"
        $backendLabel = "local.dev.properties (localhost -> 10.0.2.2 in APK)"
    } else {
        if (-not (Test-Path $LocalProps)) {
            throw "Missing $LocalProps"
        }
        Remove-Item Env:APACHIY_USE_LOCAL_DEV -ErrorAction SilentlyContinue
        $useLocalDevFlag = "false"
        $backendLabel = "local.properties (cloud)"
    }

    . (Join-Path $PSScriptRoot "Use-EmulatorOnlyAdb.ps1")

    Write-Host "Installing Android $BuildType ($appId) with $backendLabel -> :androidApp:$installTask"

    & $Gradlew ":androidApp:$installTask" "-Pnuvio.android.distribution=full" "-Pnuvio.useLocalDev=$useLocalDevFlag" @GradleArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
