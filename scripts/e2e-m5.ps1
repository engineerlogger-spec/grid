# End-to-end check of M5 (backup & restore). Uses the system file picker (DocumentsUI) for local files.
. "$PSScriptRoot\ui.ps1"

$failures = 0
function Step([string]$Name, [scriptblock]$Check) {
    try {
        $ok = & $Check
        if ($ok) { Write-Host "PASS  $Name" } else { Write-Host "FAIL  $Name"; $script:failures++ }
    } catch { Write-Host "FAIL  $Name — $($_.Exception.Message)"; $script:failures++ }
}
function ScreenText { (Get-UiNodes | ForEach-Object { "$($_.Text)|$($_.Desc)" }) -join ' ~ ' }
function Foreground { ((& $Adb shell dumpsys activity activities) | Select-String 'topResumedActivity' | Select-Object -First 1).ToString() }
function OpenBackup {
    & $Adb shell am start -n "$AppId/$Namespace.MainActivity" --es grid.launch BACKUP | Out-Null
    Wait-ForText 'Backup & restore' 15 | Out-Null; Start-Sleep 1
}

& $Adb shell am start -n "$AppId/$Namespace.MainActivity" | Out-Null
Wait-ForText 'Get started|LEFT TO SPEND' | Out-Null; Start-Sleep 1

Step 'Backup screen opens from Settings' {
    Invoke-Tap '^Home$'; Start-Sleep 1
    1..3 | ForEach-Object { & $Adb shell input swipe 540 500 540 1600 150 }
    Invoke-Tap '^Settings$'; Start-Sleep 1
    & $Adb shell input swipe 540 1500 540 700 300; Start-Sleep 0.8
    Invoke-Tap 'Not backed up yet|^Last backup'; Start-Sleep 1.2
    $t = ScreenText
    & "$PSScriptRoot\screenshot.ps1" e2e-backup | Out-Null
    ($t -match 'Connect Google Drive') -and ($t -match 'Save backup file')
}
Step 'Connecting Drive on an unconfigured build is handled gracefully' {
    Invoke-Tap '^Connect Google Drive$'; Start-Sleep 5
    $t = ScreenText; $top = Foreground
    $ok = ($t -match "isn't set up") -or ($top -match 'com.google.android.gms') -or ($t -match 'Choose an account|Sign in|Cancelled')
    if ($top -notmatch $AppId) { Send-Back; Start-Sleep 1.5 }
    & "$PSScriptRoot\screenshot.ps1" e2e-backup-drive | Out-Null
    $crash = & $Adb logcat -d -b crash
    $ok -and -not $crash
}
Step 'Save a backup file through the system picker' {
    OpenBackup
    Invoke-Tap '^Save backup file$'; Start-Sleep 2.5
    Invoke-Tap '^SAVE$|^Save$'
    Wait-ForText 'Backup saved' 10
}
Step 'Restore from that file brings the app back with the same data' {
    OpenBackup
    Invoke-Tap '^Restore from file$'; Start-Sleep 2.5
    Invoke-Tap '^grid-backup-'; Start-Sleep 2.5
    $dialog = ScreenText
    & "$PSScriptRoot\screenshot.ps1" e2e-restore-confirm | Out-Null
    Invoke-Tap '^Restore$'; Start-Sleep 6
    Wait-ForText 'LEFT TO SPEND' 25 | Out-Null
    Invoke-Tap '^Activity$'; Start-Sleep 1.5
    if (Get-UiNodes | Where-Object { $_.Desc -eq 'Clear search' }) { Invoke-Tap '^Clear search$'; Hide-Keyboard; Start-Sleep 1 }
    $after = ScreenText
    ($dialog -match 'Replace your data') -and ($after -match 'Starbucks|Groceries|Netflix')
}
Write-Host "`n$failures failure(s)"
exit $failures
