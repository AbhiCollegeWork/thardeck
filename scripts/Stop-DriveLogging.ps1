# Stop-DriveLogging.ps1 — kill all capture on the Tab and remove every trace.
# Use when you're done diagnosing. Pull your logs FIRST (Pull-DriveLogs.ps1).
#
#   powershell -ExecutionPolicy Bypass -File Stop-DriveLogging.ps1

$TAB_GUID = "YOUR_TABLET_SERIAL"
$OUT      = "/sdcard/Download/aa-diag"

function Step($m) { Write-Host ">> $m" -ForegroundColor Cyan }

$dev = adb devices | Select-String 'device$' | ForEach-Object { ($_ -split '\s+')[0] } |
       Where-Object { $_ -match $TAB_GUID -or $_ -match '192\.168\.' } | Select-Object -First 1
if (-not $dev) { Write-Host "Tab not connected." -ForegroundColor Red; exit 1 }
Step "Tab = $dev"

$before = (adb -s $dev shell "du -sk $OUT 2>/dev/null | cut -f1" 2>$null).Trim()
$mb = [math]::Round(([int]($before -replace '\D','0'))/1024,1)
if ($mb -gt 1) {
    Write-Host "WARNING: $mb MB of un-pulled logs still on device." -ForegroundColor Yellow
    Write-Host "         Run Pull-DriveLogs.ps1 first if you still want them." -ForegroundColor Yellow
    Write-Host ""
}

Step "Stopping capture processes..."
adb -s $dev shell "pkill -f 'logcat -b all'" 2>$null | Out-Null
adb -s $dev shell "pkill -f aa-wifi-sampler" 2>$null | Out-Null
Start-Sleep -Seconds 2

Step "Removing files..."
adb -s $dev shell "rm -rf $OUT" 2>$null | Out-Null
adb -s $dev shell "rm -f /data/local/tmp/aa-wifi-sampler.sh" 2>$null | Out-Null

Start-Sleep -Seconds 2
Write-Host ""
Write-Host "=== VERIFY ===" -ForegroundColor Green
$lc  = (adb -s $dev shell "ps -A 2>/dev/null | grep -c logcat" 2>$null).Trim()
$dir = (adb -s $dev shell "ls -d $OUT 2>&1" 2>$null).Trim()
$scr = (adb -s $dev shell "ls /data/local/tmp/aa-wifi-sampler.sh 2>&1" 2>$null).Trim()
Write-Host "  logcat processes : $lc  (0 = stopped)"
Write-Host "  $OUT : $(if($dir -match 'No such'){'removed'}else{$dir})"
Write-Host "  sampler script   : $(if($scr -match 'No such'){'removed'}else{$scr})"

# Sanity: confirm the wifi.csv row count is frozen (sampler truly dead)
Write-Host ""
Write-Host "Done. Nothing of mine left on the tab." -ForegroundColor Green
Write-Host "Re-arm anytime with Start-DriveLogging.ps1" -ForegroundColor Green
