# Puts the TV back the way it was: re-enables Google TV's home screen and everything setup.ps1 switched off.
#   powershell -ExecutionPolicy Bypass -File .\undo.ps1
param(
    [string]$TvHost = '192.168.0.69',
    [string]$Adb = "$env:USERPROFILE\Downloads\platform-tools-latest-windows\platform-tools\adb.exe"
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
& $Adb connect "${TvHost}:5555" | Out-Host
$env:TV_HOST = $TvHost
$env:TV_ADB_BINARY = $Adb
& .\tools\restore.ps1 -TvHost $TvHost ci\sony-vh2-debloat.txt -Stock com.google.android.apps.tv.launcherx
Write-Host 'Google TV home is back. The Men Cave apps are still installed; uninstall them from Settings > Apps if you like.'
