# Builds the release APK and installs it on a connected phone (USB or wireless debugging).
# If no phone is connected, the APK is only copied to release\ so you can transfer it by hand.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

if (-not $env:JAVA_HOME) {
    $jbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
}

& .\gradlew.bat assembleRelease
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }

$apk = 'app\build\outputs\apk\release\app-release.apk'
New-Item -ItemType Directory -Force release | Out-Null
Copy-Item $apk release\otp-forwarder.apk -Force
Write-Host "APK: $(Resolve-Path release\otp-forwarder.apk)"

$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source }
if ($adb) {
    $devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
    if ($devices) {
        & $adb install -r $apk
        if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }
        Write-Host 'Installed. Open OTP Forwarder on the phone.'
        exit 0
    }
}
Write-Host 'No phone connected via adb. Copy release\otp-forwarder.apk to the phone and open it (see README, option C).'
