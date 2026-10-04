# End-to-end check of M3 (Insights, budgets, alerts). Run after e2e-m1 and e2e-m2 on a fresh install.
. "$PSScriptRoot\ui.ps1"

$failures = 0
function Step([string]$Name, [scriptblock]$Check) {
    try {
        $ok = & $Check
        if ($ok) { Write-Host "PASS  $Name" } else { Write-Host "FAIL  $Name"; $script:failures++ }
    } catch { Write-Host "FAIL  $Name — $($_.Exception.Message)"; $script:failures++ }
}
function ScreenText { (Get-UiNodes | ForEach-Object { "$($_.Text)|$($_.Desc)" }) -join ' ~ ' }
function ScrollDown { & $Adb shell input swipe 540 1500 540 600 300; Start-Sleep 0.8 }

& $Adb shell am start -n "$AppId/$Namespace.MainActivity" | Out-Null
Wait-ForText 'Get started|LEFT TO SPEND|Home' | Out-Null; Start-Sleep 1

# After M1+M2: Groceries 25, Metro 10, Netflix 13.99, Rent 850 → 898.99 spent.
Step 'Insights KPIs match the ledger' {
    Invoke-Tap '^Insights$'; Start-Sleep 1.5
    $t = ScreenText
    & "$PSScriptRoot\screenshot.ps1" e2e-insights-top | Out-Null
    ($t -match 'SPENT') -and ($t -match '€898\.99') -and ($t -match 'INCOME')
}
Step 'Category breakdown, pace and history render' {
    $top = ScreenText
    ScrollDown; $a = ScreenText; & "$PSScriptRoot\screenshot.ps1" e2e-insights-categories | Out-Null
    ScrollDown; $b = ScreenText; & "$PSScriptRoot\screenshot.ps1" e2e-insights-pace | Out-Null
    ScrollDown; $c = ScreenText; & "$PSScriptRoot\screenshot.ps1" e2e-insights-bottom | Out-Null
    $all = "$top $a $b $c"
    ($all -match 'WHERE IT WENT') -and ($all -match 'Housing|Other') -and ($all -match 'PACE') -and ($all -match 'LAST 6 MONTHS')
}
Step 'Set a €30 Groceries budget' {
    $n = Get-UiNodes | Where-Object { $_.Text -eq 'Set budget' } | Select-Object -First 1
    if (-not $n) { ScrollDown }
    Invoke-Tap '^Set budget$'; Start-Sleep 1
    Invoke-Tap '^Restaurants$|^Groceries$|^Transport$'; Start-Sleep 0.8   # open the category dropdown
    Invoke-Tap '^Groceries$'; Start-Sleep 0.5
    $f = Get-UiNodes | Where-Object { $_.Class -eq 'EditText' } | Select-Object -First 1
    if ($f) { & $Adb shell input tap $f.X $f.Y } else { Invoke-Tap '€' }
    Send-Text '30'; Hide-Keyboard; Invoke-Tap '^Save$'; Start-Sleep 1.5
    (ScreenText) -match 'CATEGORY BUDGETS' -and (ScreenText) -match '€5\.00 left'
}
Step 'Crossing the budget posts an alert' {
    Invoke-Tap 'Add transaction'; Start-Sleep 1
    foreach ($k in '^1$', '^0$') { $n = Get-UiNodes | Where-Object { $_.Desc -match $k } | Select-Object -Last 1; & $Adb shell input tap $n.X $n.Y; Start-Sleep 0.25 }
    Invoke-Tap '^Groceries$'; Start-Sleep 4
    $notifs = (& $Adb shell dumpsys notification --noredact) -join "`n"
    $notifs -match 'Groceries is over budget'
}
Write-Host "`n$failures failure(s)"
exit $failures
