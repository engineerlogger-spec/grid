# Minimal starting state for running selected E2E suites on a fresh install: onboarding only (EUR, salary 2,500, dark).
. "$PSScriptRoot\ui.ps1"

& $Adb shell am start -n "$AppId/$Namespace.MainActivity" | Out-Null
Wait-ForText 'Get started|LEFT TO SPEND' | Out-Null; Start-Sleep 1
if (Get-UiNodes | Where-Object { $_.Text -eq 'Get started' }) {
    Invoke-Tap 'Get started'; Start-Sleep 1; Invoke-Tap '^EUR$'; Invoke-Tap '^Next$'; Start-Sleep 1
    Invoke-Tap '^Amount$'; Send-Text '2500'; & $Adb shell input keyevent KEYCODE_ENTER; Start-Sleep 0.5; Hide-Keyboard
    Invoke-Tap '^Next$'; Start-Sleep 1; Invoke-Tap '^Next$'; Start-Sleep 1; Invoke-Tap '^Dark$'; Invoke-Tap '^Next$'; Start-Sleep 1
    Invoke-Tap 'Start using Grid'; Start-Sleep 3
}
if (-not (Wait-ForText 'LEFT TO SPEND' 15)) { Write-Host "SETUP FAILED: not on Home"; exit 1 }
Write-Host "setup: onboarded"
exit 0
