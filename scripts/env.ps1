# Shared environment for all dev scripts. Dot-source it: . "$PSScriptRoot\env.ps1"
$ErrorActionPreference = 'Stop'

$Root     = Split-Path $PSScriptRoot -Parent
$Sdk      = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$Adb      = Join-Path $Sdk 'platform-tools\adb.exe'
$Emulator = Join-Path $Sdk 'emulator\emulator.exe'
$Avd      = 'Flutter_Device'

$env:JAVA_HOME    = 'C:\Program Files\Android\Android Studio\jbr'   # JDK 21, matches the project's jvmTarget
$env:ANDROID_HOME = $Sdk
$env:PATH         = "$env:JAVA_HOME\bin;$Sdk\platform-tools;$env:PATH"

# Read applicationId / namespace from the build file so scripts survive the Vaulty -> Grid rename.
$gradleFile = Get-Content (Join-Path $Root 'app\build.gradle.kts') -Raw
$AppId      = [regex]::Match($gradleFile, 'applicationId\s*=\s*"([^"]+)"').Groups[1].Value
$Namespace  = [regex]::Match($gradleFile, 'namespace\s*=\s*"([^"]+)"').Groups[1].Value
$Apk        = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'

$localProps = Join-Path $Root 'local.properties'
# Properties files need ':' escaped (C\:/Users/...); rewrite older unescaped files too.
if (-not (Test-Path $localProps) -or (Get-Content $localProps -Raw) -notmatch 'sdk\.dir=[A-Za-z]\\:') {
    Set-Content $localProps ('sdk.dir=' + (($Sdk -replace '\\', '/') -replace ':', '\:'))
}

function Get-Device {
    (& $Adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' } |
        ForEach-Object { ($_ -split '\t')[0] } | Select-Object -First 1
}

function Start-Emulator {
    if (Get-Device) { return }
    Write-Host "Booting emulator '$Avd'..."
    Start-Process $Emulator -ArgumentList '-avd', $Avd, '-no-snapshot-save', '-no-boot-anim' -WindowStyle Minimized
    & $Adb wait-for-device
    while ((& $Adb shell getprop sys.boot_completed 2>$null) -ne '1') { Start-Sleep -Seconds 2 }
    Write-Host 'Emulator ready.'
}
