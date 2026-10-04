# Save a screenshot of the device to screenshots\<name>.png.  Usage: .\scripts\screenshot.ps1 [name]
param([string]$Name = (Get-Date -Format 'yyyyMMdd-HHmmss'))
. "$PSScriptRoot\env.ps1"

$dir = Join-Path $Root 'screenshots'
New-Item -ItemType Directory -Force $dir | Out-Null
$out = Join-Path $dir "$Name.png"
& $Adb shell screencap -p /sdcard/screen.png
& $Adb pull /sdcard/screen.png $out | Out-Null
& $Adb shell rm /sdcard/screen.png
Write-Host $out
