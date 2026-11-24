# Men Cave TV

A custom Apple TV-style home screen for a Sony BRAVIA 4K VH2 (Google TV 12), built on the
[Clean Your TV](https://github.com/Gathin23/cleanyourtv) launcher (MIT, see LICENSE), plus three add-ons:

- **Weather** (from Clean Your TV, Open-Meteo, no key)
- **Verse of the Day** (NIV, Bible Gateway's free daily verse; last verse kept offline)
- **Popular Now** (popular titles on Netflix, Disney+ and Apple TV in the UAE, from TMDB)

Photos: `themes/mencave` (world landscapes from Wikimedia Commons, credits in its CREDITS.md).

## Build
Every push to `main` builds all four APKs in GitHub Actions. Download **mencave-apks** from the latest run.
Needs the repository secret `TMDB_API_KEY`.

## Install (Windows)
1. Install Python 3 (tick "Add python.exe to PATH").
2. Download this repo (Code > Download ZIP) and unzip it.
3. Unzip **mencave-apks** into an `apks` folder inside it.
4. TV: ADB debugging on. Then in PowerShell, in the unzipped folder:
   `powershell -ExecutionPolicy Bypass -File .\setup.ps1`

Undo everything: `powershell -ExecutionPolicy Bypass -File .\undo.ps1`
