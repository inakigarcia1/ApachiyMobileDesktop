# Shared launch logic for run-desktop-*.ps1

function Start-ApachiyDesktop {
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

    $runTask = if ($BuildType -eq "Debug") { "run" } else { "runRelease" }
    $buildLabel = if ($BuildType -eq "Debug") { "debug (Gradle :composeApp:run)" } else { "release (Gradle :composeApp:runRelease)" }

    if ($Backend -eq "Local") {
        if (-not (Test-Path $DevProps)) {
            if (-not (Test-Path $DevExample)) {
                throw "Missing $DevExample"
            }
            Copy-Item $DevExample $DevProps
            Write-Host "Created local.dev.properties from template. Edit backend URLs/keys if needed."
        }
        $env:APACHIY_USE_LOCAL_DEV = "1"
        $useLocalDevFlag = "true"
        $backendLabel = "local.dev.properties overlay"
    } else {
        if (-not (Test-Path $LocalProps)) {
            throw "Missing $LocalProps"
        }
        Remove-Item Env:APACHIY_USE_LOCAL_DEV -ErrorAction SilentlyContinue
        $useLocalDevFlag = "false"
        $backendLabel = "local.properties (cloud)"
    }

    Write-Host "Starting desktop $buildLabel with $backendLabel."

    & $Gradlew ":composeApp:$runTask" "-Pnuvio.useLocalDev=$useLocalDevFlag" @GradleArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
