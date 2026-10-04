# End-to-end check of M8 (bank sync) against the debug-only demo bank: connect, import, grouped review, de-duplication.
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

Step 'Demo bank connects through the app link' {
    Home
    Invoke-Tap '^Settings$'; Start-Sleep 1.2
    SwipeUp; SwipeUp
    Invoke-TapCase '^Bank sync$'; Start-Sleep 1.5
    Invoke-TapCase '^Use demo bank'; Start-Sleep 1.5
    Invoke-TapCase '^Connect Revolut$'
    $accounts = Wait-ForText 'Start sync' 20
    & "$PSScriptRoot\screenshot.ps1" e2e-bank-accounts | Out-Null
    $accounts -and ((ScreenText) -match 'not supported yet')   # the USD pocket is shown but can't be enabled
}
Step 'First sync imports three months and lands on the connected status' {
    Invoke-TapCase '^Start sync$'
    $connected = Wait-ForText '^Sync now$' 40
    & "$PSScriptRoot\screenshot.ps1" e2e-bank-connected | Out-Null
    $connected -and ((ScreenText) -match 'days of access left')
}
Step 'Review groups payments by merchant and person' {
    Launch 'DETECTED'; Start-Sleep 2
    $t = ScreenText
    & "$PSScriptRoot\screenshot.ps1" e2e-bank-review | Out-Null
    1..3 | ForEach-Object { SwipeUp; $t += ScreenText }
    ($t -match 'From Revolut') -and ($t -match 'Lidl') -and ($t -match 'J\. Dupont') -and ($t -match 'Acme SAS') -and ($t -match '3 payments')
}
Step 'One tap settles a whole group' {
    Resolve-Group 'Lidl' 'Groceries'
    Resolve-Group 'J. Dupont' 'Housing'
    Resolve-Group 'Acme SAS' 'Salary'
    Launch 'DETECTED'; Start-Sleep 2
    $t = ScreenText; SwipeUp; $t += ScreenText
    ($t -notmatch '\bLidl\b') -and ($t -notmatch 'J\. Dupont') -and ($t -notmatch 'Acme SAS')
}
Step '"My own account" counts salary-account transfers as money moved' {
    & $Adb shell am force-stop $AppId; Launch 'DETECTED'; Wait-ForText '^Detected$' 15 | Out-Null; Start-Sleep 1
    $nodes = Get-UiNodes
    $group = $nodes | Where-Object { $_.Text -ceq 'Sam Taylor' } | Select-Object -First 1
    for ($i = 0; -not $group -and $i -lt 3; $i++) { SwipeUp; $nodes = Get-UiNodes; $group = $nodes | Where-Object { $_.Text -ceq 'Sam Taylor' } | Select-Object -First 1 }
    $own = $nodes | Where-Object { $_.Text -ceq 'My own account' -and $_.Y -gt $group.Y } | Sort-Object Y | Select-Object -First 1
    & $Adb shell input tap $own.X $own.Y; Start-Sleep 1.5
    Home
    Invoke-Tap '^Savings$'; Wait-ForText 'Moved to Revolut' 10 | Out-Null
    Invoke-TapCase '^Show previous month$'; Start-Sleep 1.5
    $t = ScreenText
    & "$PSScriptRoot\screenshot.ps1" e2e-moved | Out-Null
    # Previous month: +1,500 in on the 29th, +100 card top-up, −200 sent back on the 20th.
    ($t -match 'Sam Taylor') -and ($t -match '€1,400\.00')
}
Step 'Ticking "Next month" moves a transfer into the following month' {
    $nodes = Get-UiNodes
    $row = $nodes | Where-Object { $_.Text -match '\b29\b' -and $_.Text -notmatch 'last month|next month' } | Select-Object -First 1
    $box = $nodes | Where-Object { $_.Text -ceq 'Next month' -and [math]::Abs($_.Y - $row.Y) -lt 90 } | Select-Object -First 1
    & $Adb shell input tap $box.X ($box.Y - 45); Start-Sleep 1.5
    $before = ScreenText   # previous month now: moved 100 − 200 = −100 (so saved = +100 with no salary entered)
    Invoke-TapCase '^Show next month$'; Start-Sleep 1.5
    $after = ScreenText    # this month: the 1,500 moved in
    & "$PSScriptRoot\screenshot.ps1" e2e-moved-next | Out-Null
    # Negative money is written with a typographic minus (U+2212) or a hyphen depending on the locale.
    ($before -match '[−-]€100\.00') -and ($before -notmatch '€1,400\.00') -and ($after -match '€1,500\.00') -and ($after -match 'from last month')
}
Step 'The bank copy of a captured payment is merged, not added' {
    $after = Get-ActivityRowCount 'Starbucks'
    Write-Host "      Starbucks rows: before $starbucksBefore, after $after"
    $after -eq $starbucksBefore -and $after -ge 1
}
Step 'Moves to my own vault are not spending' {
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
