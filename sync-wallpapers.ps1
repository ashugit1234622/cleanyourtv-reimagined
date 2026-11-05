param(
    [string]$TvHost = "192.168.1.36:5555"
)

$wallpapersDir = "$PSScriptRoot\wallpapers"
if (-not (Test-Path $wallpapersDir)) {
    Write-Error "Wallpapers directory not found: $wallpapersDir"
    exit 1
}

Write-Host "Connecting to TV at $TvHost..."
adb connect $TvHost

Write-Host "Pushing wallpapers from $wallpapersDir to TV..."
adb -s $TvHost shell "mkdir -p /sdcard/Android/data/com.gathin.tvhome/files/wallpapers"
adb -s $TvHost push "$wallpapersDir\*.*" /sdcard/Android/data/com.gathin.tvhome/files/wallpapers/

Write-Host "Restarting CleanYourTV to reload wallpapers..."
adb -s $TvHost shell "am force-stop com.gathin.tvhome; input keyevent 3"
Write-Host "Wallpapers synced successfully! Rotation is set to 60 minutes."

