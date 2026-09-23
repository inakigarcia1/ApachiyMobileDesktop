# Runs Apachiy Desktop against the local backend with the agent-QA sidecar enabled.
# Writes oracle files to qa/runs/current/ so a Cursor agent can read them.
#
# Usage:
#   .\scripts\run-desktop-qa.ps1

param(
    [string[]]$Args
)

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$QaDir = Join-Path $Root "qa\runs\current"
$Gradlew = Join-Path $Root "gradlew.bat"
$DevProps = Join-Path $Root "local.dev.properties"
$DevExample = Join-Path $Root "local.dev.example.properties"

if (-not (Test-Path $DevProps)) {
    if (-not (Test-Path $DevExample)) {
        Write-Error "Missing $DevExample"
    }
    Copy-Item $DevExample $DevProps
    Write-Host "Created local.dev.properties from template. Edit backend URLs/keys if needed."
}

New-Item -ItemType Directory -Force -Path $QaDir | Out-Null
Get-ChildItem -Path $QaDir -File -ErrorAction SilentlyContinue | Remove-Item -Force

$SecretsPath = Join-Path $Root "qa\apachiy-subsync-loop\secrets.local.json"
if (Test-Path $SecretsPath) {
    $env:APACHIY_AGENT_QA_ACCOUNTS_JSON = [System.IO.File]::ReadAllText($SecretsPath)
}

$env:APACHIY_USE_LOCAL_DEV = "1"
$env:APACHIY_AGENT_QA = "1"
$env:APACHIY_AGENT_QA_DIR = $QaDir

$qaDirProp = $QaDir.Replace('\', '/')
Write-Host "Agent QA sidecar -> $QaDir"
Write-Host "Starting desktop with local.dev.properties overlay."

& $Gradlew ":composeApp:run" `
    "-Pnuvio.useLocalDev=true" `
    "-Papachiy.agentQa=true" `
    "-Papachiy.agentQaDir=$qaDirProp" `
    @Args
