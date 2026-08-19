# Start-AndroidAuto.ps1 — one-shot pipeline: project S25 Ultra's Android Auto onto the Tab S9 FE+
# Usage: right-click > Run with PowerShell (or: powershell -ExecutionPolicy Bypass -File Start-AndroidAuto.ps1)
# Requires: both devices on the same Wi-Fi as this PC, wireless debugging ON (paired already).

$TAB_GUID   = "YOUR_TABLET_SERIAL"   # your tablet  (adb devices -l)
$PHONE_GUID = "YOUR_PHONE_SERIAL"   # your phone   (adb devices -l)
$ErrorActionPreference = "Continue"

function Step($msg) { Write-Host ">> $msg" -ForegroundColor Cyan }

# ---- 1. Discover + connect both devices over mDNS ----
Step "Discovering devices via mDNS..."
$services = adb mdns services 2>$null | Select-String '_adb-tls-connect'
$tabAddr = $phoneAddr = $null
foreach ($s in $services) {
    $line = $s.ToString().Trim()
    $addr = ($line -split '\s+')[-1]
    if ($line -match $TAB_GUID)   { $tabAddr = $addr }
    if ($line -match $PHONE_GUID) { $phoneAddr = $addr }
}
if (-not $tabAddr)   { Write-Host "TAB not found on network. Is it awake with wireless debugging on?" -ForegroundColor Red; exit 1 }
if (-not $phoneAddr) { Write-Host "PHONE not found on network. Is wireless debugging on?" -ForegroundColor Red; exit 1 }

adb connect $tabAddr   2>$null | Out-Null
adb connect $phoneAddr 2>$null | Out-Null
Start-Sleep -Seconds 2
# Prefer the stable mDNS-name transports if present; fall back to ip:port
$devs = adb devices | Select-String 'device$' | ForEach-Object { ($_ -split '\s+')[0] }
$TAB   = ($devs | Where-Object { $_ -match $TAB_GUID })   | Select-Object -First 1
$PHONE = ($devs | Where-Object { $_ -match $PHONE_GUID }) | Select-Object -First 1
if (-not $TAB)   { $TAB   = $tabAddr }
if (-not $PHONE) { $PHONE = $phoneAddr }
Step "Tab = $TAB"
Step "Phone = $PHONE"

# ---- 2. Tab's current wlan0 IP (survives IP changes) ----
$tabIp = (adb -s $TAB shell "ip addr show wlan0" 2>$null | Select-String 'inet (\d+\.\d+\.\d+\.\d+)').Matches.Groups[1].Value
if (-not $tabIp) { Write-Host "Could not read tab IP" -ForegroundColor Red; exit 1 }
Step "Tab IP = $tabIp"

# ---- 3. Cold-stop Android Auto on the phone (deterministic state) ----
Step "Cold-restarting Android Auto stack on phone..."
adb -s $PHONE shell "am force-stop com.google.android.projection.gearhead" 2>$null | Out-Null
Start-Sleep -Seconds 3

# ---- 4. Arm the tab receiver (launch app + tap the WiFi tile; the service intent is not exported) ----
$listening = adb -s $TAB shell "cat /proc/net/tcp /proc/net/tcp6" 2>$null | Select-String '14A8'
if (-not $listening) {
    Step "Arming Open Headunit wireless mode on tab..."
    adb -s $TAB shell "am start -n com.andrerinas.headunitrevived/com.andrerinas.openheadunit.main.MainActivity" 2>$null | Out-Null
    Start-Sleep -Seconds 4
    # WiFi tile coordinates depend on orientation; detect via screencap header (PNG width/height)
    $null = adb -s $TAB exec-out screencap -p > "$env:TEMP\ohu-orient.png" 2>$null
    $bytes = [IO.File]::ReadAllBytes("$env:TEMP\ohu-orient.png")[16..23]
    $w = [BitConverter]::ToUInt32(($bytes[3..0]), 0); $h = [BitConverter]::ToUInt32(($bytes[7..4]), 0)
    if ($w -gt $h) { adb -s $TAB shell "input tap 1553 710" 2>$null | Out-Null }   # landscape
    else           { adb -s $TAB shell "input tap 582 1418" 2>$null | Out-Null }   # portrait
    Start-Sleep -Seconds 4
    $listening = adb -s $TAB shell "cat /proc/net/tcp /proc/net/tcp6" 2>$null | Select-String '14A8'
    if (-not $listening) { Write-Host "Could not arm tab listener - open the app and tap WiFi manually" -ForegroundColor Yellow }
} else {
    Step "Tab already listening on 5288."
}

# ---- 5. Fire the wireless-startup broadcast on the phone ----
# -f 0x20 = FLAG_INCLUDE_STOPPED_PACKAGES: reaches gearhead even right after force-stop
Step "Triggering Android Auto wireless startup -> $tabIp`:5288 ..."
adb -s $PHONE shell "am broadcast -f 0x20 -n com.google.android.projection.gearhead/com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver -a com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START --es ip_address $tabIp --ei projection_port 5288 --receiver-foreground" 2>$null | Out-Null

# ---- 6. Poll for the projection session ----
Step "Waiting for projection (up to 40s)..."
$ok = $false
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 2
    $est = adb -s $TAB shell "cat /proc/net/tcp /proc/net/tcp6" 2>$null | ForEach-Object { $_ -split '\r?\n' } | Where-Object { $_ -match '14A8' -and $_ -match '\s01\s' }
    if ($est) { $ok = $true; break }
    if ($i -eq 8) {
        Step "Retrying broadcast once..."
        adb -s $PHONE shell "am broadcast -f 0x20 -n com.google.android.projection.gearhead/com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver -a com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START --es ip_address $tabIp --ei projection_port 5288 --receiver-foreground" 2>$null | Out-Null
    }
}

if ($ok) {
    Write-Host ""
    Write-Host "SUCCESS - Android Auto is projecting to the tab." -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "FAILED - no session established. Check: both devices on same Wi-Fi, tab screen on." -ForegroundColor Red
    Write-Host "Fallback that always works: USB cable phone->tab, tap the USB tile." -ForegroundColor Yellow
    exit 1
}
