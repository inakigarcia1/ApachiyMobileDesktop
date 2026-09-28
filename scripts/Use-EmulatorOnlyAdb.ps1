# Limits Gradle install* tasks to a local emulator (sets ANDROID_SERIAL).
# Skips TVs, phones, and other adb targets unless APACHIY_ALLOW_ANY_ADB_DEVICE=1.

if ($env:APACHIY_ALLOW_ANY_ADB_DEVICE -eq "1") {
    Write-Host "APACHIY_ALLOW_ANY_ADB_DEVICE=1: not restricting adb target."
    return
}

$preferred = $env:APACHIY_ADB_SERIAL
if ($preferred) {
    if ($preferred -notmatch "^emulator-") {
        Write-Error "APACHIY_ADB_SERIAL must be an emulator serial (emulator-XXXX), got: $preferred"
    }
    $env:ANDROID_SERIAL = $preferred
    Write-Host "ADB install target: $preferred (APACHIY_ADB_SERIAL)"
    return
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Error "adb not found on PATH. Start an emulator or set APACHIY_ADB_SERIAL."
}

$emulators = @()
$physical = @()
foreach ($line in (& adb devices | Select-Object -Skip 1)) {
    if ($line -match "^\s*(\S+)\s+device\s*$") {
        $serial = $Matches[1]
        if ($serial -match "^emulator-") {
            $emulators += $serial
        } else {
            $physical += $serial
        }
    }
}

if ($physical.Count -gt 0) {
    Write-Host ("Ignoring other adb devices: " + ($physical -join ", "))
}

if ($emulators.Count -eq 0) {
    Write-Error "No booted Android emulator (serial must start with emulator-). Start an AVD or set APACHIY_ADB_SERIAL=emulator-5554. Override: APACHIY_ALLOW_ANY_ADB_DEVICE=1"
}

if ($emulators.Count -gt 1) {
    Write-Host ("Multiple emulators, using " + $emulators[0] + ". Set APACHIY_ADB_SERIAL to pick another.")
}

$env:ANDROID_SERIAL = $emulators[0]
Write-Host "ADB install target: $env:ANDROID_SERIAL"
