# Run unit tests and lint; -Device also runs instrumented tests on the emulator.
# Usage: .\scripts\test.ps1 [-Device]
param([switch]$Device)
. "$PSScriptRoot\env.ps1"

Push-Location $Root
try {
    $tasks = @('testDebugUnitTest', 'lintDebug')
    if ($Device) { Start-Emulator; $tasks += 'connectedDebugAndroidTest' }
    & .\gradlew.bat @tasks --console=plain --continue
    $code = $LASTEXITCODE
    Write-Host "Reports: app\build\reports\"
    exit $code
} finally { Pop-Location }
