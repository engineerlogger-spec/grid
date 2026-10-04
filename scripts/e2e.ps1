# Full on-device regression: build, fresh install, run every milestone's E2E flow in order, report crashes.
# Usage: .\scripts\e2e.ps1 [-NoBuild]
param([switch]$NoBuild)
. "$PSScriptRoot\env.ps1"

if (-not $NoBuild) { & "$PSScriptRoot\build.ps1" | Out-Null }
Start-Emulator
& $Adb uninstall $AppId 2>$null | Out-Null
& $Adb install -r $Apk | Out-Null
& $Adb logcat -c

$total = 0
foreach ($suite in Get-ChildItem $PSScriptRoot -Filter 'e2e-m*.ps1' | Sort-Object Name) {
    Write-Host "== $($suite.BaseName)"
    & $suite.FullName
    $total += $LASTEXITCODE
}
$crashes = & $Adb logcat -d -b crash
if ($crashes) { Write-Host "== CRASHES"; $crashes | Select-Object -First 40 | ForEach-Object { Write-Host $_ }; $total++ }
Write-Host "`nE2E total failures: $total"
exit $total
