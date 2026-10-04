# End-to-end check of M6 (shortcuts, Quick Settings tile, widget, categories, app lock).
. "$PSScriptRoot\ui.ps1"

$failures = 0
function Step([string]$Name, [scriptblock]$Check) {
    try {
        $ok = & $Check
        if ($ok) { Write-Host "PASS  $Name" } else { Write-Host "FAIL  $Name"; $script:failures++ }
    } catch { Write-Host "FAIL  $Name — $($_.Exception.Message)"; $script:failures++ }
}
function ScreenText { (Get-UiNodes | ForEach-Object { "$($_.Text)|$($_.Desc)" }) -join ' ~ ' }
function Launch([string]$Target) {
    if ($Target) { & $Adb shell am start -n "$AppId/$Namespace.MainActivity" --es grid.launch $Target | Out-Null }
    else { & $Adb shell am start -n "$AppId/$Namespace.MainActivity" | Out-Null }
}

& $Adb shell am force-stop $AppId
Launch; Wait-ForText 'Get started|LEFT TO SPEND' | Out-Null; Start-Sleep 1

Step 'Launcher shortcut opens quick add' {
    Launch 'ADD_EXPENSE'; Start-Sleep 2
    $t = ScreenText; Send-Back; Start-Sleep 1
    ($t -match 'Expense') -and ($t -match 'Save')
}
Step 'Quick Settings tile opens quick add' {
    $tile = "$AppId/$Namespace.feature.widget.AddExpenseTileService"
    & $Adb shell cmd statusbar add-tile $tile | Out-Null; Start-Sleep 1
    & $Adb shell cmd statusbar expand-settings | Out-Null; Start-Sleep 1.5   # tiles are bound while the panel is open
    & $Adb shell cmd statusbar click-tile $tile | Out-Null; Start-Sleep 3
    $t = ScreenText; Send-Back; Start-Sleep 1
    & $Adb shell cmd statusbar collapse | Out-Null
    ($t -match 'Expense') -and ($t -match 'Save')
}
Step 'Home-screen widget is registered' {
    $widgets = (& $Adb shell dumpsys appwidget) -join "`n"
    $widgets -match "$([regex]::Escape($AppId))/.*GridWidgetReceiver"
}
Step 'A new category can be created and used in quick add' {
    $catName = "Espresso $(Get-Random -Minimum 100 -Maximum 999)"   # unique: duplicates are rejected by design
    Launch; Wait-ForText 'LEFT TO SPEND' 15 | Out-Null
    1..3 | ForEach-Object { & $Adb shell input swipe 540 500 540 1600 150 }
    Invoke-Tap '^Settings$'; Start-Sleep 1
    Invoke-Tap 'Add, rename, reorder'; Start-Sleep 1.2
    Invoke-Tap '^Add category$'; Start-Sleep 1.2
    Invoke-Tap '^Name$'; Send-Text $catName; Hide-Keyboard; Start-Sleep 0.5
    Invoke-Tap '^cafe$'; Start-Sleep 0.3
    & $Adb shell input swipe 540 1500 540 700 300; Start-Sleep 0.5
    Invoke-Tap '^Save$'; Start-Sleep 1.5
    1..4 | ForEach-Object { & $Adb shell input swipe 540 1600 540 400 200 }; Start-Sleep 0.8
    $list = ScreenText
    & "$PSScriptRoot\screenshot.ps1" e2e-categories | Out-Null
    Launch 'ADD_EXPENSE'; Start-Sleep 2
    $more = Get-UiNodes | Where-Object { $_.Text -eq 'More' } | Select-Object -First 1
    if ($more) { & $Adb shell input tap $more.X $more.Y; Start-Sleep 1 }
    1..3 | ForEach-Object { & $Adb shell input swipe 540 900 540 300 200 }; Start-Sleep 0.8
    $sheet = ScreenText; Send-Back; Start-Sleep 1
    ($list -match $catName) -and ($sheet -match $catName)
}
# Fresh launch to Home, entering the PIN if the app starts locked.
function LaunchHome {
    & $Adb shell am force-stop $AppId; Launch; Start-Sleep 4
    if ((ScreenText) -match 'Grid is locked|PIN') { Send-Text '1234'; & $Adb shell input keyevent KEYCODE_ENTER }
    Wait-ForText 'LEFT TO SPEND' 15 | Out-Null
}
# Opens Settings and sets the App lock switch to [On], reading its real checked state first.
function Set-AppLockSwitch([bool]$On) {
    Invoke-Tap '^Settings$'; Start-Sleep 1
    & $Adb shell input swipe 540 1500 540 700 300; Start-Sleep 0.8
    $row = Get-UiNodes | Where-Object { $_.Text -eq 'App lock' } | Select-Object -First 1
    [xml]$x = (& $Adb shell "uiautomator dump /sdcard/ui.xml > /dev/null && cat /sdcard/ui.xml") -join ''
    $switch = $x.SelectNodes('//node[@checkable="true"]') | Where-Object {
        $b = [regex]::Matches($_.bounds, '\d+') | ForEach-Object { [int]$_.Value }
        ($b[1] + $b[3]) / 2 -gt $row.Y - 80 -and ($b[1] + $b[3]) / 2 -lt $row.Y + 80
    } | Select-Object -First 1
    if ($switch -and (($switch.checked -eq 'true') -ne $On)) { & $Adb shell input tap 960 $row.Y; Start-Sleep 1 }
}

Step 'App lock locks after a restart and the phone PIN unlocks it' {
    & $Adb shell locksettings set-pin 1234 | Out-Null; Start-Sleep 1
    try {
        LaunchHome
        Set-AppLockSwitch $true
        & $Adb shell am force-stop $AppId; Start-Sleep 1
        Launch; Start-Sleep 4
        $locked = ScreenText
        & "$PSScriptRoot\screenshot.ps1" e2e-locked | Out-Null
        Send-Text '1234'; & $Adb shell input keyevent KEYCODE_ENTER
        $unlocked = Wait-ForText 'LEFT TO SPEND' 15
        ($locked -match 'Grid is locked|PIN') -and $unlocked
    } finally {
        # Leave the device clean: app lock off, then the PIN removed.
        LaunchHome
        Set-AppLockSwitch $false
        Send-Back
        & $Adb shell locksettings clear --old 1234 | Out-Null
    }
}
Write-Host "`n$failures failure(s)"
exit $failures
