# Start-DriveLogging.ps1 — arm persistent capture on the Tab for the next drive(s).
# Survives adb disconnect, screen off, and the tab moving to the car hotspot.
# Does NOT survive a tab reboot — re-run after any reboot.
#
# Usage:  powershell -ExecutionPolicy Bypass -File Start-DriveLogging.ps1
# Later:  powershell -ExecutionPolicy Bypass -File Pull-DriveLogs.ps1

$TAB_GUID = "YOUR_TABLET_SERIAL"
$OUT = "/sdcard/Download/aa-diag"

function Step($m) { Write-Host ">> $m" -ForegroundColor Cyan }

# --- find the tab on whatever transport is available (USB or wireless) ---
$dev = adb devices | Select-String 'device$' | ForEach-Object { ($_ -split '\s+')[0] } |
       Where-Object { $_ -match $TAB_GUID -or $_ -match '192\.168\.' } | Select-Object -First 1
if (-not $dev) {
    Write-Host "Tab not connected. Plug in USB, or re-pair wireless debugging." -ForegroundColor Red
    exit 1
}
Step "Tab = $dev"

adb -s $dev shell "mkdir -p $OUT" 2>$null | Out-Null

# --- stop any previous capture so we don't stack duplicates ---
Step "Clearing any previous capture..."
adb -s $dev shell "pkill -f 'logcat -b all' ; pkill -f aa-wifi-sampler" 2>$null | Out-Null
Start-Sleep -Seconds 1

# --- 1. rotating logcat: 8 files x 16MB = 128MB ceiling, all buffers ---
Step "Starting rotating logcat capture..."
adb -s $dev shell "nohup setsid sh -c 'logcat -b all -v threadtime -r 16384 -n 8 -f $OUT/logcat.txt' >/dev/null 2>&1 &" 2>$null | Out-Null

# --- 2. Wi-Fi link sampler: the time-series that actually diagnoses the corruption ---
# Captures RSSI / link speed / Rx speed / frequency / SSID every 10s.
$sampler = @"
nohup setsid sh -c 'echo aa-wifi-sampler; while true; do
  TS=`$(date +%Y-%m-%d_%H:%M:%S);
  W=`$(dumpsys wifi 2>/dev/null | grep -m1 mWifiInfo);
  SSID=`$(echo "`$W" | grep -oE "SSID: \"[^\"]*\"" | head -1);
  RSSI=`$(echo "`$W" | grep -oE "RSSI: -?[0-9]+" | head -1);
  LINK=`$(echo "`$W" | grep -oE "Link speed: [0-9]+Mbps" | head -1);
  RX=`$(echo "`$W" | grep -oE "Rx Link speed: [0-9]+Mbps" | head -1);
  FREQ=`$(echo "`$W" | grep -oE "Frequency: [0-9]+MHz" | head -1);
  EST=`$(cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '"'"'`$4=="01" && `$8==10373'"'"' | wc -l);
  echo "`$TS|`$SSID|`$RSSI|`$LINK|`$RX|`$FREQ|aa_session=`$EST" >> $OUT/wifi.csv;
  sleep 10;
done' >/dev/null 2>&1 &
"@
Step "Starting Wi-Fi link sampler (10s interval)..."
adb -s $dev shell $sampler 2>$null | Out-Null

Start-Sleep -Seconds 12

# --- verify both are actually running ---
Step "Verifying..."
$lc = (adb -s $dev shell "ps -A 2>/dev/null | grep -c 'logcat'" 2>$null).Trim()
$sz = (adb -s $dev shell "ls -la $OUT 2>/dev/null" 2>$null)
Write-Host ""
Write-Host "logcat processes running: $lc"
Write-Host "$OUT contents:"
Write-Host $sz
Write-Host ""
$rows = (adb -s $dev shell "wc -l < $OUT/wifi.csv 2>/dev/null" 2>$null).Trim()
if ([int]($rows -replace '\D','0') -ge 1) {
    Write-Host "SUCCESS - both capturing. wifi.csv has $rows row(s) already." -ForegroundColor Green
    Write-Host "Drive normally. Afterwards run Pull-DriveLogs.ps1" -ForegroundColor Green
} else {
    Write-Host "WARNING - wifi.csv not written yet; check manually." -ForegroundColor Yellow
}
