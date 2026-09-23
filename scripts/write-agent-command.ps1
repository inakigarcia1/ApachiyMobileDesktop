param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("seek", "play", "pause", "open_meta", "play_episode", "open_url")]
    [string]$Action,
    [long]$PositionMs,
    [string]$MetaType,
    [string]$MetaId,
    [int]$Season,
    [int]$Episode,
    [string]$Url,
    [string]$Id = ([guid]::NewGuid().ToString("N").Substring(0, 8))
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$QaDir = Join-Path $Root "qa\runs\current"
New-Item -ItemType Directory -Force -Path $QaDir | Out-Null

switch ($Action) {
    "seek" {
        if (-not $PSBoundParameters.ContainsKey("PositionMs")) { throw "seek requires -PositionMs" }
    }
    "open_meta" {
        if ([string]::IsNullOrWhiteSpace($MetaType) -or [string]::IsNullOrWhiteSpace($MetaId)) {
            throw "open_meta requires -MetaType and -MetaId"
        }
    }
    "play_episode" {
        if ([string]::IsNullOrWhiteSpace($MetaType) -or [string]::IsNullOrWhiteSpace($MetaId) -or
            -not $PSBoundParameters.ContainsKey("Season") -or -not $PSBoundParameters.ContainsKey("Episode")) {
            throw "play_episode requires -MetaType -MetaId -Season -Episode"
        }
    }
    "open_url" {
        if ([string]::IsNullOrWhiteSpace($Url)) { throw "open_url requires -Url" }
    }
}

$payload = [ordered]@{
    id = $Id
    action = $Action
}
if ($PSBoundParameters.ContainsKey("PositionMs")) { $payload.positionMs = $PositionMs }
if (-not [string]::IsNullOrWhiteSpace($MetaType)) { $payload.metaType = $MetaType }
if (-not [string]::IsNullOrWhiteSpace($MetaId)) { $payload.metaId = $MetaId }
if ($PSBoundParameters.ContainsKey("Season")) { $payload.season = $Season }
if ($PSBoundParameters.ContainsKey("Episode")) { $payload.episode = $Episode }
if (-not [string]::IsNullOrWhiteSpace($Url)) { $payload.url = $Url }

$json = ($payload | ConvertTo-Json -Compress)
$target = Join-Path $QaDir "command.json"
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($target, $json, $utf8NoBom)
Write-Host "Wrote $target"
Write-Host $json
