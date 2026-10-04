# End-to-end check of the M1 flows on a running emulator (app already onboarded).
# Usage: .\scripts\e2e-m1.ps1      → prints PASS/FAIL per step, saves screenshots to screenshots\e2e-*.png
. "$PSScriptRoot\ui.ps1"

$failures = 0
function Step([string]$Name, [scriptblock]$Check) {
    try {
        $ok = & $Check
        if ($ok) { Write-Host "PASS  $Name" } else { Write-Host "FAIL  $Name"; $script:failures++ }
    } catch { Write-Host "FAIL  $Name — $($_.Exception.Message)"; $script:failures++ }
    # Leave no sheet open so one failure doesn't cascade into the next step.
    if ((Get-UiNodes | Where-Object { $_.Text -eq 'Expense' })) { Send-Back; Start-Sleep -Milliseconds 800 }
}
function ScreenText { (Get-UiNodes | ForEach-Object { "$($_.Text)|$($_.Desc)" }) -join ' ~ ' }
function Keys([string]$Amount) { foreach ($ch in $Amount.ToCharArray()) { $k = if ($ch -eq '.') { '^Decimal point$' } elseif ($ch -eq '+') { '^Plus$' } elseif ($ch -eq '-') { '^Minus$' } else { "^$ch$" }; Invoke-TapLabeled $k } }
# Keypad keys expose their label as onClickLabel/text; digits are plain text nodes.
function Invoke-TapLabeled([string]$Pattern) {
    $n = Get-UiNodes | Where-Object { $_.Text -match $Pattern -or $_.Desc -match $Pattern } | Select-Object -Last 1
    if (-not $n) { throw "No element '$Pattern'" }
    & $Adb shell input tap $n.X $n.Y; Start-Sleep -Milliseconds 250
}
function OpenAdd { Invoke-Tap 'Add transaction'; Start-Sleep -Milliseconds 900 }

& $Adb shell am start -n "$AppId/$Namespace.MainActivity" | Out-Null
Wait-ForText 'Get started|LEFT TO SPEND' | Out-Null; Start-Sleep 1

if (Get-UiNodes | Where-Object { $_.Text -eq 'Get started' }) {
    Step 'Onboarding: EUR, salary 2,500, 80% goal, dark' {
        Invoke-Tap 'Get started'; Start-Sleep 1; Invoke-Tap '^EUR$'; Invoke-Tap '^Next$'; Start-Sleep 1
        Invoke-Tap '^Amount$'; Send-Text '2500'; & $Adb shell input keyevent KEYCODE_ENTER; Start-Sleep 0.5; Hide-Keyboard
        Invoke-Tap '^Next$'; Start-Sleep 1; Invoke-Tap '^Next$'; Start-Sleep 1; Invoke-Tap '^Dark$'; Invoke-Tap '^Next$'; Start-Sleep 1
        Invoke-Tap 'Start using Grid'; Start-Sleep 3
        (ScreenText) -match 'LEFT TO SPEND'
    }
}

Step 'Quick add: 12.50 + tap Restaurants saves in two actions' {
    OpenAdd; Keys '12.5'; Invoke-Tap '^Restaurants$'; Start-Sleep 1.5
    $t = ScreenText; ($t -match 'Saved €12\.50 · Restaurants') -and ($t -notmatch 'Tap a category')
}
Step 'Undo removes the saved spend' {
    Invoke-Tap '^Undo$'; Start-Sleep 1.5; (ScreenText) -notmatch 'Restaurants.*€12\.50'
}
Step 'Calculator: 20+5 then Groceries saves 25' {
    OpenAdd; Keys '20+5'; $mid = ScreenText; Invoke-Tap '^Groceries$'; Start-Sleep 1.5
    ($mid -match '20 \+ 5') -and ((ScreenText) -match 'Saved €25\.00 · Groceries')
}
Step 'Category tap without amount asks for one' {
    OpenAdd; Invoke-Tap '^Transport$'; Start-Sleep 0.5; $t = ScreenText; Send-Back; Start-Sleep 0.8; $t -match 'Enter an amount first'
}
Step 'Long-press selects, Save stores with note' {
    OpenAdd; Keys '8.9'
    $n = Get-UiNodes | Where-Object { $_.Text -eq 'Transport' } | Select-Object -First 1
    & $Adb shell input swipe $n.X $n.Y $n.X $n.Y 700; Start-Sleep 0.5
    Invoke-Tap '^Note$'; Start-Sleep 0.5; Invoke-Tap 'What was it'; Send-Text 'Metro'; & $Adb shell input keyevent KEYCODE_ENTER; Start-Sleep 0.8
    Invoke-TapLabeled '^Save$'; Start-Sleep 1.5; (ScreenText) -match 'Saved €8\.90 · Transport'
}
Step 'Income toggle saves income' {
    OpenAdd; Invoke-Tap '^Income$'; Start-Sleep 0.5; Keys '150'; Invoke-Tap '^Freelance$'; Start-Sleep 1.5
    (ScreenText) -match 'Saved €150\.00 · Freelance'
}
Step 'Home reflects spending (spent €33.90)' {
    Start-Sleep 1; (ScreenText) -match '€33\.90'
}
Step 'Edit from Recent changes amount' {
    & $Adb shell input swipe 540 1500 540 500 300; Start-Sleep 1   # Recent is below the fold
    Invoke-Tap '^Metro$'; Start-Sleep 1; $t = ScreenText
    Invoke-TapLabeled '^Delete$'; Invoke-TapLabeled '^Delete$'; Invoke-TapLabeled '^Delete$'; Keys '10'; Invoke-TapLabeled '^Save$'; Start-Sleep 1.5
    ($t -match 'Edit transaction') -and ((ScreenText) -match 'Updated')
}
& "$PSScriptRoot\screenshot.ps1" e2e-home | Out-Null
Step 'Activity lists and searches' {
    Invoke-Tap '^Activity$'; Start-Sleep 1.2; $all = ScreenText
    Invoke-Tap '^Search$'; Send-Text 'metro'; Start-Sleep 1; $filtered = ScreenText
    & "$PSScriptRoot\screenshot.ps1" e2e-activity | Out-Null
    ($all -match 'Groceries') -and ($filtered -match 'Metro') -and ($filtered -notmatch 'Groceries')
}
Hide-Keyboard
Write-Host "`n$failures failure(s)"
exit $failures
