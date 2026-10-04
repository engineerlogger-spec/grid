# UI automation helpers for driving the app from scripts. Dot-source: . .\scripts\ui.ps1
. "$PSScriptRoot\env.ps1"

# List on-screen elements that have text or a content description, with their tap centers.
function Get-UiNodes {
    # The emulator's adb link occasionally drops for a moment; retry instead of failing the whole flow.
    for ($i = 0; $i -lt 5; $i++) {
        & $Adb wait-for-device
        & $Adb shell rm -f /sdcard/ui.xml   # never read a previous dump if this one fails
        & $Adb shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
        $raw = (& $Adb shell cat /sdcard/ui.xml 2>$null) -join ''
        if ($raw -like '<?xml*') { break }
        Start-Sleep -Seconds 1
    }
    [xml]$xml = $raw
    $xml.SelectNodes('//node') | Where-Object { $_.text -or $_.'content-desc' } | ForEach-Object {
        $b = [regex]::Matches($_.bounds, '\d+') | ForEach-Object { [int]$_.Value }
        [pscustomobject]@{
            Text = $_.text; Desc = $_.'content-desc'; Class = ($_.class -split '\.')[-1]
            X = [int](($b[0] + $b[2]) / 2); Y = [int](($b[1] + $b[3]) / 2)
        }
    }
}

# Tap the first element whose text or content description matches the pattern.
function Invoke-Tap([string]$Pattern) {
    $n = Get-UiNodes | Where-Object { $_.Text -match $Pattern -or $_.Desc -match $Pattern } | Select-Object -First 1
    if (-not $n) { throw "No UI element matching '$Pattern'" }
    & $Adb shell input tap $n.X $n.Y
    Start-Sleep -Milliseconds 800
}

function Send-Text([string]$Text) {
    # One character per command: bulk `input text` drops keystrokes on fields that recompose per change.
    foreach ($ch in $Text.ToCharArray()) {
        & $Adb shell input text ($(if ($ch -eq ' ') { '%s' } else { "$ch" }))
    }
    Start-Sleep -Milliseconds 500
}

function Send-Back { & $Adb shell input keyevent KEYCODE_BACK; Start-Sleep -Milliseconds 800 }

# Close the soft keyboard only if it is showing (a blind BACK would leave the screen instead).
function Hide-Keyboard {
    if ((& $Adb shell dumpsys input_method) -match 'mInputShown=true') { Send-Back }
}
