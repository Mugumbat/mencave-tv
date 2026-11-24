# Men Cave TV: install the latest apps and theme over an existing setup, then take a screenshot of Home.
#   powershell -ExecutionPolicy Bypass -File .\update.ps1
param(
    [string]$TvHost = '192.168.0.69',
    [string]$Adb = "$env:USERPROFILE\Downloads\platform-tools-latest-windows\platform-tools\adb.exe"
)
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
Set-Location -LiteralPath $PSScriptRoot
$t = "${TvHost}:5555"
& $Adb connect $t | Out-Host
$env:TV_HOST = $TvHost
$env:TV_ADB_BINARY = $Adb

Write-Host '== Installing the apps' -ForegroundColor Cyan
# Push, then let the TV's own package manager install: "adb install" can sit waiting for an on-screen
# Play Protect prompt on this TV and quietly not install.
foreach ($apk in 'launcher', 'allapps', 'movies', 'verse', 'weather') {
    if (-not (Test-Path "apks\$apk.apk")) { continue }
    & $Adb -s $t push "apks\$apk.apk" "/data/local/tmp/mc-$apk.apk" | Out-Null
    $out = (& $Adb -s $t shell "pm install -r /data/local/tmp/mc-$apk.apk; rm -f /data/local/tmp/mc-$apk.apk") -join ' '
    Write-Host "$apk : $out"
    if ($out -notmatch 'Success') { throw "$apk did not install: $out" }
}
& $Adb -s $t shell "dumpsys package com.gathin.tvhome | grep lastUpdateTime" | Out-Host

Write-Host '== Which TOD package is installed (for the dock)' -ForegroundColor Cyan
& $Adb -s $t shell "pm list packages | grep -iE 'tod|bein'" | Out-Host

Write-Host '== Pushing the theme' -ForegroundColor Cyan
& .\tools\theme.ps1 -TvHost $TvHost themes\mencave
Set-Location -LiteralPath $PSScriptRoot

Write-Host '== Home screenshot (waits 20 s for the verse and fixtures to load)' -ForegroundColor Cyan
& $Adb -s $t shell input keyevent KEYCODE_HOME
Start-Sleep -Seconds 20
& $Adb -s $t shell screencap -p /sdcard/mencave-home.png
& $Adb -s $t pull /sdcard/mencave-home.png "screenshot-home.png" | Out-Host
& $Adb -s $t shell rm /sdcard/mencave-home.png
& $Adb -s $t shell wm size | Out-Host
& $Adb -s $t shell wm density | Out-Host
Write-Host 'Done. Screenshot saved as screenshot-home.png in this folder.' -ForegroundColor Green
