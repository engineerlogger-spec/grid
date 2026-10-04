# On-device regression: build, fresh install, run E2E suites in order, report crashes.
# Usage: .\scripts\e2e.ps1 [-NoBuild] [-Suites m4,m8]
#   No -Suites: every suite (full regression, ~15 min) — before shipping.
#   -Suites:    only those, after a minimal onboarding (~3 min) — while iterating on one area.
param([switch]$NoBuild, [string[]]$Suites)
. "$PSScriptRoot\env.ps1"

if (-not $NoBuild) { & "$PSScriptRoot\build.ps1" | Out-Null }
Start-Emulator
& $Adb uninstall $AppId 2>$null | Out-Null
& $Adb install -r $Apk | Out-Null
& $Adb logcat -c

$all = Get-ChildItem $PSScriptRoot -Filter 'e2e-m*.ps1' | Sort-Object Name
$selected = if ($Suites) { $all | Where-Object { $_.BaseName -replace '^e2e-', '' -in $Suites } } else { $all }
$total = 0
if ($Suites -and -not ($Suites -contains 'm1')) {
    & "$PSScriptRoot\e2e-setup.ps1"
    $total += $LASTEXITCODE
}
foreach ($suite in $selected) {
    Write-Host "== $($suite.BaseName)"
    & $suite.FullName
    $total += $LASTEXITCODE
}
$crashes = & $Adb logcat -d -b crash
if ($crashes) { Write-Host "== CRASHES"; $crashes | Select-Object -First 40 | ForEach-Object { Write-Host $_ }; $total++ }
Write-Host "`nE2E total failures: $total"
exit $total
