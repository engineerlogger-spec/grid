# End-to-end check of M2 (Bills) on a running emulator, app already onboarded (run e2e-m1 first).
. "$PSScriptRoot\ui.ps1"

$failures = 0
function Step([string]$Name, [scriptblock]$Check) {
    try {
        $ok = & $Check
        if ($ok) { Write-Host "PASS  $Name" } else { Write-Host "FAIL  $Name"; $script:failures++ }
    } catch { Write-Host "FAIL  $Name — $($_.Exception.Message)"; $script:failures++ }
}
function ScreenText { (Get-UiNodes | ForEach-Object { "$($_.Text)|$($_.Desc)" }) -join ' ~ ' }
function ScrollDown { & $Adb shell input swipe 540 1500 540 500 300; Start-Sleep 0.8 }
function OpenActivity { Invoke-Tap '^Activity$'; Start-Sleep 1.2; if (Get-UiNodes | Where-Object { $_.Desc -eq 'Clear search' }) { Invoke-Tap '^Clear search$'; Hide-Keyboard; Start-Sleep 1 } }
function ScrollTop { 1..3 | ForEach-Object { & $Adb shell input swipe 540 500 540 1600 150 }; Start-Sleep 0.5 }

& $Adb shell pm grant $AppId android.permission.POST_NOTIFICATIONS 2>$null
& $Adb shell am start -n "$AppId/$Namespace.MainActivity" | Out-Null
Wait-ForText 'Get started|LEFT TO SPEND' | Out-Null; Start-Sleep 1

Step 'Bills tab shows subscriptions empty state' {
    Invoke-Tap '^Bills$'; Start-Sleep 1.2; (ScreenText) -match 'No subscriptions yet'
}
Step 'Add Netflix from presets, charging today, auto-logs' {
    Invoke-Tap '^Add subscription$'; Start-Sleep 1.2
    Invoke-Tap '^Netflix$'; Start-Sleep 0.5
    Invoke-Tap '^Amount$'; Send-Text '13.99'; & $Adb shell input keyevent KEYCODE_ENTER; Start-Sleep 0.5; Hide-Keyboard
    Invoke-Tap '^Save$'; Start-Sleep 2
    $t = ScreenText
    & "$PSScriptRoot\screenshot.ps1" e2e-bills-subs | Out-Null
    ($t -match 'PER MONTH') -and ($t -match 'Netflix') -and ($t -match '€13\.99')
}
Step 'Netflix charge appears in Activity' {
    OpenActivity; $t = ScreenText; Invoke-Tap '^Bills$'; Start-Sleep 1
    $t -match 'Netflix'
}
Step 'Add pending Rent (due in 7 days) shows on Home Upcoming' {
    Invoke-Tap '^Pending$'; Start-Sleep 0.8
    Invoke-Tap '^Add pending payment$'; Start-Sleep 1.2
    Invoke-Tap 'What is it'; Send-Text 'Rent'; Hide-Keyboard; Start-Sleep 0.5
    Invoke-Tap '^Amount$'; Send-Text '850'; & $Adb shell input keyevent KEYCODE_ENTER; Start-Sleep 0.5; Hide-Keyboard
    Invoke-Tap '^Save$'; Start-Sleep 2
    $bills = ScreenText
    & "$PSScriptRoot\screenshot.ps1" e2e-bills-pending | Out-Null
    Invoke-Tap '^Home$'; Start-Sleep 1.5; ScrollDown; $homeText = ScreenText
    & "$PSScriptRoot\screenshot.ps1" e2e-home-upcoming | Out-Null
    ScrollTop
    ($bills -match 'TO PAY') -and ($bills -match 'Rent') -and ($homeText -match 'UPCOMING') -and ($homeText -match 'Rent')
}
Step 'Mark paid books the expense' {
    Invoke-Tap '^Bills$'; Start-Sleep 1; Invoke-Tap '^Pending$'; Start-Sleep 0.8
    Invoke-Tap '^Mark paid$'; Start-Sleep 1.5; $t = ScreenText
    OpenActivity; $a = ScreenText
    ($t -match 'Marked paid') -and ($t -match 'SETTLED') -and ($a -match 'Rent')
}
Step 'Reminder notification posts for a bill due tomorrow' {
    Invoke-Tap '^Bills$'; Start-Sleep 1; Invoke-Tap '^Pending$'; Start-Sleep 0.8
    Invoke-Tap '^Add pending payment$'; Start-Sleep 1.2
    Invoke-Tap 'What is it'; Send-Text 'Dentist'; Hide-Keyboard; Start-Sleep 0.5
    Invoke-Tap '^Amount$'; Send-Text '60'; & $Adb shell input keyevent KEYCODE_ENTER; Start-Sleep 0.5; Hide-Keyboard
    # Due date: pick tomorrow in the date dialog.
    $tomorrow = (Get-Date).AddDays(1)
    Invoke-Tap '\d{1,2},? \d{4}|\w{3} \d{1,2}, \d{4}'; Start-Sleep 1
    $label = $tomorrow.ToString('dddd, MMMM d, yyyy', [Globalization.CultureInfo]::GetCultureInfo('en-US'))
    $n = Get-UiNodes | Where-Object { $_.Desc -match [regex]::Escape($label) -or $_.Text -match [regex]::Escape($label) } | Select-Object -First 1
    if ($n) { & $Adb shell input tap $n.X $n.Y; Start-Sleep 0.5 } else { & $Adb shell input tap 540 1200 }
    Invoke-Tap '^Done$'; Start-Sleep 0.5
    Invoke-Tap '^Save$'; Start-Sleep 6
    $notifs = (& $Adb shell dumpsys notification --noredact) -join "`n"
    $notifs -match 'Dentist is due tomorrow'
}
Write-Host "`n$failures failure(s)"
exit $failures
