# End-to-end check of M4 (payment detection) through the real NotificationListenerService.
# Debug builds treat shell-posted notifications titled "[Revolut] …" as Revolut notifications.
. "$PSScriptRoot\ui.ps1"

$failures = 0
function Step([string]$Name, [scriptblock]$Check) {
    try {
        $ok = & $Check
        if ($ok) { Write-Host "PASS  $Name" } else { Write-Host "FAIL  $Name"; $script:failures++ }
    } catch { Write-Host "FAIL  $Name — $($_.Exception.Message)"; $script:failures++ }
}
function ScreenText { (Get-UiNodes | ForEach-Object { "$($_.Text)|$($_.Desc)" }) -join ' ~ ' }
function Post([string]$Tag, [string]$Title, [string]$Text) {
    & $Adb shell "cmd notification post -S bigtext -t '$Title' $Tag '$Text'" | Out-Null
}
function GridNotifications { (& $Adb shell dumpsys notification --noredact) -join "`n" }

& $Adb shell cmd notification allow_listener "$AppId/$Namespace.feature.capture.PaymentCaptureService" | Out-Null
& $Adb shell am start -n "$AppId/$Namespace.MainActivity" | Out-Null
Wait-ForText 'Get started|LEFT TO SPEND' | Out-Null; Start-Sleep 1

Step 'Settings shows detection as on' {
    Invoke-Tap '^Home$'; Start-Sleep 1
    1..3 | ForEach-Object { & $Adb shell input swipe 540 500 540 1600 150 }
    Invoke-Tap '^Settings$'; Start-Sleep 1.2
    $t = ScreenText; Send-Back; Start-Sleep 1
    $t -match 'Reading Google Wallet, PayPal and Revolut'
}
Step 'A Revolut payment is detected and queued' {
    Post 'grid_e2e_1' '[Revolut] Revolut' 'Paid 4.50 EUR at Starbucks'
    Start-Sleep 4
    $n = GridNotifications
    Wait-ForText 'DETECTED' 10 | Out-Null
    $homeText = ScreenText
    & "$PSScriptRoot\screenshot.ps1" e2e-home-detected | Out-Null
    ($n -match '€4\.50 at Starbucks') -and ($homeText -match 'new payment')
}
Step 'Categorising from the inbox books the spend' {
    Invoke-Tap 'new payment'; Start-Sleep 1.5
    & "$PSScriptRoot\screenshot.ps1" e2e-detected | Out-Null
    # First suggested category of the newest card (suggestions follow the user's habits).
    $amount = Get-UiNodes | Where-Object { $_.Text -match '^€' } | Select-Object -First 1
    $chip = Get-UiNodes | Where-Object { $_.Y -gt $amount.Y + 60 -and $_.Text -and $_.Text -notmatch '€|Revolut' } | Sort-Object Y, X | Select-Object -First 1
    $category = $chip.Text; & $Adb shell input tap $chip.X $chip.Y; Start-Sleep 1.5
    $t = ScreenText
    Send-Back; Start-Sleep 1
    Invoke-Tap '^Activity$'; Start-Sleep 1.2
    if (Get-UiNodes | Where-Object { $_.Desc -eq 'Clear search' }) { Invoke-Tap '^Clear search$'; Hide-Keyboard; Start-Sleep 1 }
    $a = ScreenText
    ($t -match "Added to $category") -and ($a -match 'Starbucks')
}
Step 'The same merchant is now added automatically' {
    Post 'grid_e2e_2' '[Revolut] Revolut' 'Paid 6.20 EUR at Starbucks'
    Start-Sleep 4
    (GridNotifications) -match 'Added €6\.20 · Starbucks'
}
Step 'Unrecognised payment notifications land in diagnostics' {
    Post 'grid_e2e_3' '[Revolut] Revolut' 'Payment at Uber'
    Start-Sleep 3
    & $Adb shell am start -n "$AppId/$Namespace.MainActivity" --es grid.launch DETECTED | Out-Null
    Start-Sleep 2
    $t = ScreenText
    & "$PSScriptRoot\screenshot.ps1" e2e-detected-diagnostics | Out-Null
    Send-Back; Start-Sleep 1
    ($t -match 'NOT RECOGNISED') -and ($t -match 'Payment at Uber')
}
Write-Host "`n$failures failure(s)"
exit $failures
