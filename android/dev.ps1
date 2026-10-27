# Build, install and launch Homebase on a TV over Wi-Fi adb.
# Usage: .\dev.ps1 [-Device 192.168.1.171:5555] [-Screenshot]
param(
    [string]$Device = "192.168.1.171:5555",
    [switch]$Screenshot
)
$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
# installDebug otherwise targets every connected device
$env:ANDROID_SERIAL = $Device

adb connect $Device | Out-Null
Push-Location $PSScriptRoot
try {
    .\gradlew.bat installDebug --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally { Pop-Location }

adb shell am start -W -n com.homebase.tv/.MainActivity | Out-Null

if ($Screenshot) {
    Start-Sleep 5
    $out = Join-Path $env:TEMP "homebase.png"
    adb shell screencap -p /sdcard/homebase.png
    adb pull /sdcard/homebase.png $out | Out-Null
    adb shell rm /sdcard/homebase.png
    Write-Host "Screenshot: $out"
}
