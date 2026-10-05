# End-to-end check of M8 (bank sync) against the debug-only demo bank: connect, whole-history import, automatic sorting, savings.
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
function SwipeUp { & $Adb shell input swipe 540 1500 540 700 250; Start-Sleep 0.6 }
# A cold start always lands on Home (a plain relaunch would resume whatever screen was open).
function Home { & $Adb shell am force-stop $AppId; Launch; Wait-ForText 'LEFT TO SPEND' 15 | Out-Null }

# Activity rows titled exactly [Title] after searching for it (the search field itself doesn't count).
function Get-ActivityRowCount([string]$Title) {
    Home
    Invoke-TapCase '^Activity$'; Start-Sleep 1.2
    Invoke-Tap '^Search$'; Send-Text $Title; Start-Sleep 1.2
    $rows = @(Get-UiNodes | Where-Object { $_.Text -ceq $Title -and $_.Class -ne 'EditText' }).Count
    Hide-Keyboard; Send-Back
    $rows
}

# In Detected: pick [Category] for the bank group titled [Title] through its "More…" sheet.
function Resolve-Group([string]$Title, [string]$Category) {
    # Cold start: a resumed Detected screen keeps its old scroll position.
    & $Adb shell am force-stop $AppId; Launch 'DETECTED'; Wait-ForText '^Detected$' 15 | Out-Null; Start-Sleep 1
    # (PowerShell names are case-insensitive: the node must not be called $title, or it overwrites $Title.)
    $nodes = Get-UiNodes
    $groupNode = $nodes | Where-Object { $_.Text -ceq $Title } | Select-Object -First 1
    for ($i = 0; -not $groupNode -and $i -lt 3; $i++) { SwipeUp; $nodes = Get-UiNodes; $groupNode = $nodes | Where-Object { $_.Text -ceq $Title } | Select-Object -First 1 }
    if (-not $groupNode) { throw "No review group '$Title'" }
    if ($groupNode.Y -gt 1200) {
        # Near the bottom edge its chips may be off-screen: bring the card up first.
        & $Adb shell input swipe 540 1400 540 900 300; Start-Sleep 0.8
        $nodes = Get-UiNodes; $groupNode = $nodes | Where-Object { $_.Text -ceq $Title } | Select-Object -First 1
    }
    $more = $nodes | Where-Object { $_.Text -match '^More' -and $_.Y -gt $groupNode.Y } | Sort-Object Y | Select-Object -First 1
    & $Adb shell input tap $more.X $more.Y; Start-Sleep 1.2
    Invoke-TapCase "^$Category$"; Start-Sleep 1.5
}

& $Adb shell cmd notification allow_listener "$AppId/$Namespace.feature.capture.PaymentCaptureService" | Out-Null
& $Adb shell am force-stop $AppId
Launch; Wait-ForText 'Get started|LEFT TO SPEND' | Out-Null; Start-Sleep 1

# A Revolut card payment the phone already saw as a notification: the bank's record of it must merge, not duplicate.
& $Adb shell "cmd notification post -S bigtext -t '[Revolut] Revolut' grid_e2e_m8 'Paid 4.50 EUR at Starbucks'" | Out-Null
Start-Sleep 4
Launch 'DETECTED'; Start-Sleep 2
$card = Get-UiNodes | Where-Object { $_.Text -ceq 'Starbucks' } | Select-Object -First 1
if ($card) {
    $chip = Get-UiNodes | Where-Object { $_.Y -gt $card.Y + 60 -and $_.Text -and $_.Text -notmatch '€|Revolut' } | Sort-Object Y, X | Select-Object -First 1
    if ($chip) { & $Adb shell input tap $chip.X $chip.Y; Start-Sleep 1.5 }
}
$starbucksBefore = Get-ActivityRowCount 'Starbucks'
$starbucksBefore = Get-ActivityRowCount 'Starbucks'

Step 'Demo bank connects and imports straight away' {
    Home
    Invoke-Tap '^Settings$'; Start-Sleep 1.2
    SwipeUp; SwipeUp
    Invoke-TapCase '^Bank sync$'; Start-Sleep 1.5
    Invoke-TapCase '^Use demo bank'; Start-Sleep 1.5
    Invoke-TapCase '^Connect Revolut$'
    $connected = Wait-ForText '^Sync now$' 40
    & "$PSScriptRoot\screenshot.ps1" e2e-bank-connected | Out-Null
    $connected -and ((ScreenText) -match 'days of access left') -and ((ScreenText) -match 'not supported yet')
}
Step 'Payments are categorised automatically' {
    Home
    Invoke-TapCase '^Activity$'; Start-Sleep 1.2
    Invoke-Tap '^Search$'; Send-Text 'Lidl'; Start-Sleep 1.2
    $t = ScreenText; Hide-Keyboard; Send-Back
    & "$PSScriptRoot\screenshot.ps1" e2e-bank-activity | Out-Null
    ($t -match 'Lidl') -and ($t -match 'Groceries')
}
Step 'Money moved between own accounts shows in Activity' {
    Home
    Invoke-TapCase '^Activity$'; Start-Sleep 1.2
    Invoke-TapCase '^All months$'; Start-Sleep 1   # the demo's transfers are dated last month
    Invoke-Tap '^Search$'; Send-Text 'Sam'; Start-Sleep 1.2
    $t = ScreenText; Hide-Keyboard; Send-Back
    ($t -match 'Sam Taylor') -and ($t -match 'Moved from your account') -and ($t -match 'Sent to your account')
}
Step 'Unknown payments are counted under Other and sorted with one tap' {
    Home
    $link = Wait-ForText 'under Other' 10
    Resolve-Group 'J. Dupont' 'Housing'
    Launch 'DETECTED'; Start-Sleep 2
    $t = ScreenText; SwipeUp; $t += ScreenText
    & "$PSScriptRoot\screenshot.ps1" e2e-bank-sort | Out-Null
    $link -and ($t -notmatch 'J\. Dupont')
}
Step 'Savings: salary minus money moved, with the salary set on the card' {
    Home
    Invoke-Tap '^Savings$'; Wait-ForText 'Moved to Revolut' 10 | Out-Null
    Invoke-TapCase '^Show previous month$'; Start-Sleep 1.5
    $moved = ScreenText   # previous month: +1,500 in on the 29th, +100 card top-up, -200 sent back on the 20th
    Invoke-TapCase '^Edit$'; Start-Sleep 1
    Invoke-TapCase '^Salary$'; Start-Sleep 0.5   # the field's label (the card's label is in capitals)
    Send-Text '2500'; Start-Sleep 0.5
    Invoke-TapCase '^Done$'; Start-Sleep 1.5
    $t = ScreenText
    & "$PSScriptRoot\screenshot.ps1" e2e-moved | Out-Null
    ($moved -match '€1,400\.00') -and ($t -match '€2,500\.00') -and ($t -match '€1,100\.00')
}
Step 'Ticking "Next month" moves a transfer into the following month' {
    $nodes = Get-UiNodes
    # Sam Taylor's transfer of the 29th (the demo's card top-up can fall on the same day).
    $row = $nodes | Where-Object { $_.Text -match '\b29\b' -and $_.Text -notmatch 'last month|next month' } |
        Where-Object { $r = $_; $nodes | Where-Object { $_.Text -ceq 'Sam Taylor' -and ($r.Y - $_.Y) -gt 0 -and ($r.Y - $_.Y) -lt 70 } } | Select-Object -First 1
    $box = $nodes | Where-Object { $_.Text -ceq 'Next month' -and [math]::Abs($_.Y - $row.Y) -lt 90 } | Select-Object -First 1
    & $Adb shell input tap $box.X ($box.Y - 45); Start-Sleep 1.5
    $before = ScreenText   # previous month now: moved 100 - 200 = -100
    Invoke-TapCase '^Show next month$'; Start-Sleep 1.5
    $after = ScreenText    # this month: the 1,500 moved in
    & "$PSScriptRoot\screenshot.ps1" e2e-moved-next | Out-Null
    # Negative money is written with a typographic minus (U+2212) or a hyphen depending on the locale.
    ($before -match '[\u2212-]€100\.00') -and ($before -notmatch '€1,400\.00') -and ($after -match '€1,500\.00') -and ($after -match 'from last month')
}
Step 'The bank copy of a captured payment is merged, not added' {
    $after = Get-ActivityRowCount 'Starbucks'
    Write-Host "      Starbucks rows: before $starbucksBefore, after $after"
    $after -eq $starbucksBefore -and $after -ge 1
}
Step 'Moves to my own vault are not shown' {
    (Get-ActivityRowCount 'To EUR Vault') -eq 0
}
Step 'Syncing again finds nothing new' {
    Launch 'BANK'; Wait-ForText '^Sync now$' 15 | Out-Null
    Invoke-TapCase '^Sync now$'
    Wait-ForText 'nothing new' 20
}
Send-Back
Write-Host "`n$failures failure(s)"
exit $failures
