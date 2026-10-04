# Prints the SHA-1 fingerprint of the signing key, needed for the Google Drive OAuth client.
# Usage: .\scripts\sha1.ps1                      → debug key (~/.android/debug.keystore)
#        .\scripts\sha1.ps1 -Keystore my.jks -Alias grid
param([string]$Keystore = (Join-Path $env:USERPROFILE '.android\debug.keystore'), [string]$Alias = 'androiddebugkey', [string]$StorePass = 'android')
. "$PSScriptRoot\env.ps1"

$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
$out = & $keytool -list -v -keystore $Keystore -alias $Alias -storepass $StorePass 2>&1
$sha1 = ($out | Select-String 'SHA1:').ToString().Split('SHA1:')[-1].Trim()
Write-Host "Package: $AppId"
Write-Host "SHA-1:   $sha1"
