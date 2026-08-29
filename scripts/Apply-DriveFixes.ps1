# Apply-DriveFixes.ps1
#
# Applies the settings that keep a drive healthy, on both devices.
#
# The important one is the battery saver policy. Battery Saver has to stay ON,
# because hosting the hotspot drains and heats the phone, but by default it
# throttles location, which is what makes the car icon and heading arrow
# disappear and the map stop following. Android exposes the policy separately
# from the on/off switch, so location can be exempted while every other saving
# stays in force.
#
#   powershell -ExecutionPolicy Bypass -File Apply-DriveFixes.ps1
#   powershell -ExecutionPolicy Bypass -File Apply-DriveFixes.ps1 -Revert

param([switch]$Revert)

$RECEIVER = "com.andrerinas.headunitrevived"
$MAPS     = "com.google.android.apps.maps"
$AA       = "com.google.android.projection.gearhead"

function Step($m) { Write-Host ">> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "   $m" -ForegroundColor Green }
function Warn($m) { Write-Host "   $m" -ForegroundColor Yellow }

# ---- find both devices -------------------------------------------------------
$devs = adb devices | Select-String 'device$' | ForEach-Object { ($_ -split '\s+')[0] }
$tab = $phone = $null
foreach ($d in $devs) {
    $model = (adb -s $d shell getprop ro.product.model 2>$null).Trim()
    if ($model -match 'X610|Tab')  { $tab = $d }
    if ($model -match 'S938|S25')  { $phone = $d }
}
if (-not $tab)   { Warn "Tablet not connected" }
if (-not $phone) { Warn "Phone not connected. Enable wireless debugging, or plug in USB." }
if (-not $tab -and -not $phone) { exit 1 }

# ---- phone -------------------------------------------------------------------
if ($phone) {
    Step "Phone ($phone)"
    if ($Revert) {
        adb -s $phone shell "settings delete global battery_saver_constants" 2>$null | Out-Null
        Ok "battery saver policy restored to default"
    } else {
        # Keep Battery Saver enabled, but stop it throttling location.
        # location_mode=0 is NO_CHANGE: GPS behaves exactly as it would with
        # the saver off. Every other restriction the saver applies still holds.
        adb -s $phone shell "settings put global battery_saver_constants 'location_mode=0'" 2>$null | Out-Null
        $bsc = (adb -s $phone shell "settings get global battery_saver_constants" 2>$null).Trim()
        Ok "battery saver policy: $bsc"

        # Battery Saver stays on. This is deliberate: the hotspot is the drain,
        # and the saver is what keeps the phone usable on a long drive.
        adb -s $phone shell "settings put global low_power 1" 2>$null | Out-Null
        $lp = (adb -s $phone shell "settings get global low_power" 2>$null).Trim()
        Ok "battery saver: $lp (1 = on, as intended)"

        foreach ($p in @($MAPS, $AA)) {
            adb -s $phone shell "dumpsys deviceidle whitelist +$p" 2>$null | Out-Null
            adb -s $phone shell "am set-standby-bucket $p active" 2>$null | Out-Null
        }
        Ok "navigation exempt from battery optimisation"
    }
}

# ---- tablet ------------------------------------------------------------------
if ($tab) {
    Step "Tablet ($tab)"
    if ($Revert) {
        adb -s $tab shell "am set-standby-bucket $RECEIVER working_set" 2>$null | Out-Null
        Ok "receiver returned to the default standby bucket"
    } else {
        # The receiver was being killed mid-projection. Doze exemption alone
        # does not prevent it; app standby is a separate mechanism.
        adb -s $tab shell "am set-standby-bucket $RECEIVER active" 2>$null | Out-Null
        adb -s $tab shell "dumpsys deviceidle whitelist +$RECEIVER" 2>$null | Out-Null
        $b = (adb -s $tab shell "am get-standby-bucket $RECEIVER" 2>$null).Trim()
        Ok "receiver standby bucket: $b (5 = exempted, 10 = active)"

        $mem = (adb -s $tab shell "cat /proc/meminfo | grep MemAvailable" 2>$null).Trim()
        Ok "tablet $mem"
    }
}

if (-not $Revert) {
    Write-Host ""
    Write-Host "Still to do by hand, because these are not settable over adb:" -ForegroundColor Yellow
    Write-Host "  Tablet  Settings > Battery > Background usage limits >"
    Write-Host "          Never sleeping apps > add Open Headunit"
    Write-Host "  Tablet  close Spotify, Chrome and Telegram before driving."
    Write-Host "          They hold roughly 640 MB between them and the tablet"
    Write-Host "          plays no audio, so nothing needs Spotify running on it."
    Write-Host "  Both    consider dropping projection to 720p. The phone encodes"
    Write-Host "          the video, so this is the largest single lever on how"
    Write-Host "          hot and how thirsty it gets."
}
