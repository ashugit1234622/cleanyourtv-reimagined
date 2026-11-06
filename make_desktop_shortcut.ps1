$WshShell = New-Object -ComObject WScript.Shell
$DesktopPath = [Environment]::GetFolderPath('Desktop')
$ShortcutPath = Join-Path $DesktopPath "Sync TV Wallpapers.lnk"
$TargetPath = Join-Path $DesktopPath "Sync TV Wallpapers.bat"

$Shortcut = $WshShell.CreateShortcut($ShortcutPath)
$Shortcut.TargetPath = $TargetPath
$Shortcut.WorkingDirectory = "C:\Users\Ashirwad\.gemini\antigravity\scratch\cleanyourtv"
$Shortcut.IconLocation = "shell32.dll,301"
$Shortcut.Description = "Sync wallpapers to Clean Your TV Launcher on Xiaomi TV (No Duplicates)"
$Shortcut.Save()

Write-Host "Created shortcut at $ShortcutPath"

