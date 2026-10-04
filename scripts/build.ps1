# Build the debug APK.  Usage: .\scripts\build.ps1 [-Clean]
param([switch]$Clean)
. "$PSScriptRoot\env.ps1"

Push-Location $Root
try {
    $tasks = @()
    if ($Clean) { $tasks += 'clean' }
    $tasks += 'assembleDebug'
    & .\gradlew.bat @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Build failed (exit $LASTEXITCODE)" }
    Write-Host "APK: $Apk"
} finally { Pop-Location }
