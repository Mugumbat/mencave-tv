# Men Cave TV: one-shot setup for the Sony BRAVIA (Google TV 12, BRAVIA_VH2_PA).
#
# Before running:
#   1. Python 3 installed (python.org, tick "Add python.exe to PATH").
#   2. This repo unzipped, and the four APKs from the GitHub "mencave-apks" download put in an "apks" folder here.
#   3. ADB debugging on, and the TV answering `adb devices` from this laptop.
#
# Run from this folder in PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\setup.ps1
#
# Everything here is reversible: packages are DISABLED, never uninstalled. Undo with .\undo.ps1.
param(
    [string]$TvHost = '192.168.0.69',
    [string]$Adb = "$env:USERPROFILE\Downloads\platform-tools-latest-windows\platform-tools\adb.exe",
    [switch]$SkipDebloat
)
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
Set-Location -LiteralPath $PSScriptRoot

function Step($text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }

# --- Checks -------------------------------------------------------------------------------------------------
if (-not (Test-Path -LiteralPath $Adb)) { throw "adb.exe not found at $Adb. Pass -Adb <path to adb.exe>." }
foreach ($apk in 'launcher', 'weather', 'verse', 'movies') {
    if (-not (Test-Path -LiteralPath "apks\$apk.apk")) { throw "apks\$apk.apk is missing. Unzip the mencave-apks download into an 'apks' folder here." }
}
$python = (Get-Command py -ErrorAction SilentlyContinue)

Step 'Connecting to the TV'
& $Adb connect "${TvHost}:5555" | Out-Host
$state = (& $Adb -s "${TvHost}:5555" get-state 2>$null)
if ("$state".Trim() -ne 'device') { throw "The TV at $TvHost is not answering adb. Check ADB debugging is on and accept the prompt on the TV." }

# The repo's tools talk to the TV through this same, already-authorised adb.
$env:TV_HOST = $TvHost
$env:TV_ADB_BINARY = $Adb

Step 'Python helper environment (first run only)'
# A Python that can actually build a venv: "embeddable" copies (a pythonXY.zip, no Lib folder) that some apps
# install, e.g. C:\Python314, cannot, so each candidate is tested rather than trusted.
function Test-Python($exe) {
    if (-not $exe -or -not (Test-Path -LiteralPath $exe)) { return $false }
    $ErrorActionPreference = 'Continue'
    $null = & $exe -c "import venv, ensurepip, encodings" 2>$null
    return ($LASTEXITCODE -eq 0)
}
$venvPy = 'tools\venv\Scripts\python.exe'
if ((Test-Path $venvPy) -and -not (Test-Python (Resolve-Path $venvPy).Path)) {
    Write-Host 'Removing a broken helper environment from an earlier run.'
    Remove-Item -Recurse -Force 'tools\venv'
}
if (-not (Test-Path $venvPy)) {
    $candidates = @()
    $candidates += Get-ChildItem "$env:LOCALAPPDATA\Programs\Python\Python3*\python.exe" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | ForEach-Object FullName
    $candidates += Get-ChildItem "$env:ProgramFiles\Python3*\python.exe" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | ForEach-Object FullName
    if ($python) { $candidates += @(& py -0p 2>$null | ForEach-Object { ($_ -split '\s+')[-1] } | Where-Object { $_ -like '*.exe' }) }
    $candidates += @(Get-Command python -All -ErrorAction SilentlyContinue | ForEach-Object Source | Where-Object { $_ -notlike '*WindowsApps*' })
    $good = $candidates | Where-Object { Test-Python $_ } | Select-Object -First 1
    if (-not $good) {
        throw "No usable Python found (tried: $($candidates -join ', ')). Install Python 3 from python.org with 'Add python.exe to PATH' ticked, close this window, open a new one and run setup again. Nothing was changed on the TV."
    }
    Write-Host "Using $good"
    # A PYTHONHOME or PYTHONPATH left by another app points Python at the wrong standard library.
    Remove-Item Env:PYTHONHOME, Env:PYTHONPATH -ErrorAction SilentlyContinue
    & $good -m venv tools\venv
    if (-not (Test-Python (Resolve-Path $venvPy -ErrorAction SilentlyContinue).Path)) { throw 'Could not create the Python helper environment. Nothing was changed on the TV.' }
    & $venvPy -m pip install --quiet -r tools\requirements.txt
    if ($LASTEXITCODE -ne 0) { throw 'Installing the Python helper packages failed (internet?). Nothing was changed on the TV.' }
}
Remove-Item Env:PYTHONHOME, Env:PYTHONPATH -ErrorAction SilentlyContinue

Step 'Backup of the TV''s current state'
New-Item -ItemType Directory -Force backup | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
& $Adb -s "${TvHost}:5555" shell pm list packages -d > "backup\disabled-before-$stamp.txt"
& $Adb -s "${TvHost}:5555" shell pm list packages > "backup\packages-before-$stamp.txt"
& $Adb -s "${TvHost}:5555" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "backup\home-before-$stamp.txt"
Write-Host "Saved in backup\"

if (-not $SkipDebloat) {
    Step 'Switching off Sony/Google trackers, promos and preloads (and the setup wizard that holds Home)'
    & .\tools\disable.ps1 -TvHost $TvHost ci\sony-vh2-debloat.txt
}

Step 'Installing Weather, Verse of the Day and Popular Now'
foreach ($apk in 'weather', 'verse', 'movies') {
    & $Adb -s "${TvHost}:5555" install -r "apks\$apk.apk" | Out-Host
}

Step 'Installing the launcher and making it Home'
New-Item -ItemType Directory -Force 'launcher\app\build\outputs\apk\release' | Out-Null
Copy-Item 'apks\launcher.apk' 'launcher\app\build\outputs\apk\release\app-release.apk' -Force
$env:SKIP_BUILD = '1'
& .\launcher\build-and-install.ps1 -TvHost $TvHost

Step 'Pushing the Men Cave theme (photos, dock apps)'
& .\tools\theme.ps1 -TvHost $TvHost themes\mencave

Write-Host ''
Write-Host 'Done. Press Home on the remote.' -ForegroundColor Green
Write-Host 'The first time, the launcher asks whether to show the Weather, Verse and Popular Now cards: choose Show.'
Write-Host 'When you are happy, switch ADB debugging OFF on the TV (Settings > System > Developer options).'
