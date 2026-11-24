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
# The first builds were signed with a throwaway key; from now on every build uses one fixed key. An app still
# carrying an old key is removed and installed fresh, once (its saved settings go with it).
$pkgs = @{ launcher = 'com.gathin.tvhome'; allapps = 'io.github.mugumbat.allapps'; movies = 'io.github.mugumbat.movies';
           verse = 'io.github.mugumbat.verse'; weather = 'io.github.sumyabazar1.weather' }
foreach ($apk in 'allapps', 'movies', 'verse', 'weather', 'launcher') {
    if (-not (Test-Path "apks\$apk.apk")) { continue }
    $tmp = "/data/local/tmp/mc-$apk.apk"
    & $Adb -s $t push "apks\$apk.apk" $tmp | Out-Null
    $out = (& $Adb -s $t shell "pm install -r $tmp") -join ' '
    if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
        Write-Host "$apk : old signing key on the TV, reinstalling it fresh"
        if ($apk -eq 'launcher') {
            # Uninstall and install in one shell line, so Home is without a launcher for a second at most. If the
            # install fails, Google TV's own home is switched back on so the TV is never left without one.
            $out = (& $Adb -s $t shell "pm uninstall $($pkgs[$apk]) >/dev/null; pm install $tmp || pm enable com.google.android.apps.tv.launcherx") -join ' '
            & $Adb -s $t shell "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION; cmd package compile -m speed -f com.gathin.tvhome; am start -a android.intent.action.MAIN -c android.intent.category.HOME" | Out-Null
        } else {
            $out = (& $Adb -s $t shell "pm uninstall $($pkgs[$apk]) >/dev/null; pm install $tmp") -join ' '
        }
    }
    & $Adb -s $t shell "rm -f $tmp" | Out-Null
    Write-Host "$apk : $out"
    if ($out -notmatch 'Success') { throw "$apk did not install: $out" }
}
& $Adb -s $t shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" | Out-Host
& $Adb -s $t shell "dumpsys package com.gathin.tvhome | grep lastUpdateTime" | Out-Host

Write-Host '== Which TOD package is installed (for the dock)' -ForegroundColor Cyan
& $Adb -s $t shell "pm list packages | grep -iE 'tod|bein'" | Out-Host

Write-Host '== Pushing the theme' -ForegroundColor Cyan
& .\tools\theme.ps1 -TvHost $TvHost themes\mencave
Set-Location -LiteralPath $PSScriptRoot

# Moves focus to TOD (row 1, column 4) wherever it starts: down past the last row, up one, far left, right three.
function Focus-Tod {
    foreach ($k in 'DOWN','DOWN','DOWN','UP','LEFT','LEFT','LEFT','LEFT','RIGHT','RIGHT','RIGHT') {
        & $Adb -s $t shell input keyevent "KEYCODE_DPAD_$k"
        Start-Sleep -Milliseconds 350
    }
    Start-Sleep -Seconds 2
}
function Shot($name) {
    & $Adb -s $t shell screencap -p /sdcard/mc-shot.png
    & $Adb -s $t pull /sdcard/mc-shot.png "$name" | Out-Null
    & $Adb -s $t shell rm /sdcard/mc-shot.png
    Write-Host "saved $name"
}
$home = 'com.gathin.tvhome/.MainActivity'

Write-Host '== Screenshots at 1080p (live, then the long-text layout test)' -ForegroundColor Cyan
& $Adb -s $t shell input keyevent KEYCODE_HOME
Start-Sleep -Seconds 20
Focus-Tod; Shot 'shot-1080p-live.png'
& $Adb -s $t shell am start -n $home --es mencave_test long | Out-Null
Start-Sleep -Seconds 3
Focus-Tod; Shot 'shot-1080p-test.png'

Write-Host '== Screenshots at native 4K (screen settings are put back afterwards)' -ForegroundColor Cyan
try {
    & $Adb -s $t shell wm size 3840x2160
    & $Adb -s $t shell wm density 640
    Start-Sleep -Seconds 8
    & $Adb -s $t shell am start -n $home --es mencave_test long | Out-Null
    Start-Sleep -Seconds 4
    Focus-Tod; Shot 'shot-4k-test.png'
    & $Adb -s $t shell input keyevent KEYCODE_HOME
    Start-Sleep -Seconds 4
    Focus-Tod; Shot 'shot-4k-live.png'
} finally {
    & $Adb -s $t shell wm size 1920x1080
    & $Adb -s $t shell wm density reset
    & $Adb -s $t shell input keyevent KEYCODE_HOME
}
& $Adb -s $t shell wm size | Out-Host
& $Adb -s $t shell wm density | Out-Host
Write-Host 'Done. Tell Claude.' -ForegroundColor Green
