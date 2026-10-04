# Build, install and launch the app on the emulator (booted if needed).
# Usage: .\scripts\run.ps1 [-NoBuild] [-Fresh] [-Logs]
#   -Fresh  uninstall first (wipes the app's database)
#   -Logs   stream the app's logcat after launch
param([switch]$NoBuild, [switch]$Fresh, [switch]$Logs)
. "$PSScriptRoot\env.ps1"

if (-not $NoBuild) { & "$PSScriptRoot\build.ps1" }
Start-Emulator

if ($Fresh) { & $Adb uninstall $AppId | Out-Null }
& $Adb install -r $Apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed' }

& $Adb logcat -c
& $Adb shell am start -n "$AppId/$Namespace.MainActivity"

if ($Logs) { & "$PSScriptRoot\logs.ps1" }
