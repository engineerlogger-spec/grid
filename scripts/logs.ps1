# Stream logcat for the running app only (plus crashes).  Usage: .\scripts\logs.ps1 [-Dump]
#   -Dump  print what's buffered and exit instead of streaming
param([switch]$Dump)
. "$PSScriptRoot\env.ps1"

$appPid = (& $Adb shell pidof $AppId).Trim()
if (-not $appPid) { Write-Warning "$AppId is not running; showing crash buffer only."; & $Adb logcat -d -b crash; return }

$logArgs = @("--pid=$appPid", '-v', 'time')
if ($Dump) { $logArgs += '-d' }
& $Adb logcat @logArgs
