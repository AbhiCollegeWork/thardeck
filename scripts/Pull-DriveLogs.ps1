# Pull-DriveLogs.ps1 — retrieve the drive capture from the Tab, summarise it, then clean the device.
# Device-side files are deleted ONLY after the local copy is verified.
#
#   powershell -ExecutionPolicy Bypass -File Pull-DriveLogs.ps1
#   powershell -ExecutionPolicy Bypass -File Pull-DriveLogs.ps1 -KeepOnDevice   (skip cleanup)
#   powershell -ExecutionPolicy Bypass -File Pull-DriveLogs.ps1 -StopLogging    (also stop capturing)

param(
    [switch]$KeepOnDevice,
    [switch]$StopLogging
)

$TAB_GUID = "YOUR_TABLET_SERIAL"
$OUT      = "/sdcard/Download/aa-diag"
$LOCAL    = "$PSScriptRoot\drive-logs\$(Get-Date -Format 'yyyy-MM-dd_HHmm')"

function Step($m) { Write-Host ">> $m" -ForegroundColor Cyan }

$dev = adb devices | Select-String 'device$' | ForEach-Object { ($_ -split '\s+')[0] } |
       Where-Object { $_ -match $TAB_GUID -or $_ -match '192\.168\.' } | Select-Object -First 1
if (-not $dev) { Write-Host "Tab not connected." -ForegroundColor Red; exit 1 }
Step "Tab = $dev"

# --- how much is on the device before we start ---
$before = (adb -s $dev shell "du -sk $OUT 2>/dev/null | cut -f1" 2>$null).Trim()
Step "On device: $([math]::Round(([int]($before -replace '\D','0'))/1024,1)) MB"

New-Item -ItemType Directory -Force -Path $LOCAL | Out-Null
Step "Pulling to $LOCAL ..."
adb -s $dev pull $OUT $LOCAL 2>&1 | Select-Object -Last 2

# --- VERIFY the pull before deleting anything ---
$localFiles = Get-ChildItem -Path $LOCAL -Recurse -File -ErrorAction SilentlyContinue
$localBytes = ($localFiles | Measure-Object Length -Sum).Sum
$devCount   = [int](((adb -s $dev shell "ls -1 $OUT 2>/dev/null | wc -l" 2>$null).Trim()) -replace '\D','0')

Write-Host ""
Write-Host "pulled files : $($localFiles.Count)  (device had $devCount)"
Write-Host "pulled size  : $([math]::Round($localBytes/1MB,1)) MB"

$pullOk = ($localFiles.Count -ge $devCount) -and ($devCount -gt 0) -and ($localBytes -gt 1024)

# ---------------- SUMMARY ----------------
$csv = Get-ChildItem -Path $LOCAL -Recurse -Filter "wifi.csv" | Select-Object -First 1
if ($csv) {
    $lines = Get-Content $csv.FullName
    Write-Host ""
    Write-Host "=== LINK QUALITY SUMMARY ===" -ForegroundColor Green
    Write-Host "samples: $($lines.Count)"

    $bad = $lines | Where-Object { $_ -match 'Rx Link speed: (\d+)Mbps' -and [int]$Matches[1] -lt 100 }
    Write-Host ""
    Write-Host "Rx-collapse events (<100Mbps): $($bad.Count)" -ForegroundColor $(if($bad.Count -gt 0){'Red'}else{'Green'})
    if ($bad.Count -gt 0) {
        $bad | Select-Object -First 15 | ForEach-Object { Write-Host "   $_" }
        Write-Host "   ^ strong RSSI (better than -60) during these = INTERFERENCE, not range" -ForegroundColor Yellow
    }

    Write-Host ""
    Write-Host "Frequencies seen:" -ForegroundColor Green
    $lines | ForEach-Object { if ($_ -match 'Frequency: (\d+)MHz') { $Matches[1] } } |
        Group-Object | Sort-Object Count -Descending |
        ForEach-Object { Write-Host "   $($_.Name) MHz  x$($_.Count)" }
    Write-Host "   (5745 = old colliding channel; 24xx = 2.4GHz fix holding)" -ForegroundColor Yellow

    $sess = ($lines | Where-Object { $_ -match 'session=[1-9]' }).Count
    Write-Host ""
    Write-Host "samples with a live AA session: $sess" -ForegroundColor Green
}

$logs = Get-ChildItem -Path $LOCAL -Recurse -Filter "logcat*" -ErrorAction SilentlyContinue
if ($logs) {
    Write-Host ""
    Write-Host "=== DECODER / APP ERRORS ===" -ForegroundColor Green
    $hits = $logs | ForEach-Object {
        Select-String -Path $_.FullName -Pattern 'MediaCodec.*[Ee]rror|CodecException|c2\.exynos.*(error|fail)|OPENHU.*(rror|ail)' -ErrorAction SilentlyContinue
    }
    Write-Host "matches: $($hits.Count)"
    $hits | Select-Object -First 20 | ForEach-Object { Write-Host "   $($_.Line.Trim())" }
}

# ---------------- CLEANUP ----------------
Write-Host ""
if ($StopLogging) {
    Step "Stopping capture on device..."
    adb -s $dev shell "pkill -f 'logcat -b all'; pkill -f aa-wifi-sampler" 2>$null | Out-Null
    adb -s $dev shell "rm -f /data/local/tmp/aa-wifi-sampler.sh" 2>$null | Out-Null
    Write-Host "Capture stopped. Re-arm later with Start-DriveLogging.ps1" -ForegroundColor Yellow
}

if ($KeepOnDevice) {
    Write-Host "Device files kept (-KeepOnDevice)." -ForegroundColor Yellow
}
elseif ($pullOk) {
    Step "Local copy verified - clearing device..."
    # Delete captured data. If still logging, logcat re-creates its file immediately.
    adb -s $dev shell "rm -f $OUT/logcat.txt* $OUT/wifi.csv" 2>$null | Out-Null
    Start-Sleep -Seconds 2
    $after = (adb -s $dev shell "du -sk $OUT 2>/dev/null | cut -f1" 2>$null).Trim()
    $freed = ([int]($before -replace '\D','0') - [int]($after -replace '\D','0'))/1024
    Write-Host "Device cleaned. Freed ~$([math]::Round($freed,1)) MB." -ForegroundColor Green
    if (-not $StopLogging) {
        Write-Host "Still capturing - fresh files start now (no re-arm needed)." -ForegroundColor Green
    }
}
else {
    Write-Host "PULL LOOKED INCOMPLETE - device files NOT deleted." -ForegroundColor Red
    Write-Host "  local files: $($localFiles.Count), device files: $devCount, size: $([math]::Round($localBytes/1MB,1)) MB" -ForegroundColor Red
    Write-Host "  Re-run, or pull manually before cleaning." -ForegroundColor Red
}

Write-Host ""
Write-Host "Logs saved in: $LOCAL" -ForegroundColor Green
