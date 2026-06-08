# Build, install, launch, and capture cobalt boot logs in one shot
$ErrorActionPreference = "SilentlyContinue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$proj = "D:\Projects\PULLOUT"

Set-Location $proj
Write-Host "=== Building ==="
& ".\gradlew.bat" assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL|BUILD FAILED|error:" | Select-Object -First 3

Write-Host "=== Installing ==="
& $adb -s R5CT31LB06P install -r "app\build\outputs\apk\debug\app-debug.apk" 2>&1 | Select-Object -Last 1

Write-Host "=== Launching ==="
& $adb -s R5CT31LB06P shell am force-stop com.andrometa.pullout
& $adb -s R5CT31LB06P logcat -c
& $adb -s R5CT31LB06P shell am start -n "com.andrometa.pullout/.MainActivity" | Out-Null
Start-Sleep -Seconds 18

Write-Host "=== Cobalt boot logs ==="
& $adb -s R5CT31LB06P logcat -d > "$env:TEMP\pullout_boot.txt" 2>&1
Get-Content "$env:TEMP\pullout_boot.txt" | Select-String "NODEJS-MOBILE|NodeServerManager|cobalt|listening|healthy|:9000|internal tunnel" | Select-Object -First 22
