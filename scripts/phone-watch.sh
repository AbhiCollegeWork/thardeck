#!/system/bin/sh
# phone-watch v2 - source-side capture.
#
# Two gaps in v1 that this fixes:
#   1. `pidof com.google.android.projection.gearhead` never matched, because
#      Android Auto runs under a suffixed process name. It reported "dead"
#      for an entire drive while projection was plainly working.
#   2. Location was not captured at all, so the missing car icon and the
#      frozen map could not be attributed. That was the actual fault.
#
# Row every 5s:
#  timestamp|projecting|gearhead|helper|bt_acl|screen|batt_temp|saver|loc_provider|loc_acc_m|loc_age_s
OUT=/sdcard/Download/aa-diag
mkdir -p $OUT
echo "timestamp|projecting|gearhead|helper|bt_acl|screen|batt_temp|saver|loc_provider|loc_acc_m|loc_age_s" >> $OUT/phone.csv

start_logcat() {
  logcat -c 2>/dev/null
  logcat -v threadtime -r 8192 -n 10 -f $OUT/phone-logcat.txt \
    "GH.*:V" CAR.SETUP:V CarService:V ActivityManager:I WifiService:I \
    SoftApManager:I bt_stack:W BluetoothAdapter:I A2dpService:I ThermalService:I \
    GnssLocationProvider:V LocationManagerService:V GnssHal:V gps:V \
    LocationProviderProxy:V FusedLocationProvider:V power:I \
    >/dev/null 2>&1 &
}

start_logcat
echo "phone-watch v2 started $(date +%Y-%m-%d_%H:%M:%S)" >> $OUT/phone-events.log

while true; do
  if [ "$(ps -A 2>/dev/null | grep -c '[l]ogcat')" -eq 0 ]; then
    echo "$(date +%Y-%m-%d_%H:%M:%S)|WATCHDOG|logcat restarted" >> $OUT/phone-events.log
    start_logcat
  fi

  TS=$(date +%Y-%m-%d_%H:%M:%S)
  PROJ=$(cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '$3 ~ /14A8$/ && $4 == "01"' | wc -l)
  # match any gearhead process, whatever suffix it carries
  GH=$(ps -A -o PID,NAME 2>/dev/null | grep "gearhead" | head -1 | awk '{print $1}')
  HP=$(pidof com.andrerinas.wirelesshelper 2>/dev/null | awk '{print $1}')
  BT=$(dumpsys bluetooth_manager 2>/dev/null | grep -c "ACL BR/EDR:Y")
  SCR=$(dumpsys power 2>/dev/null | grep -m1 -oE 'mWakefulness=[A-Za-z]+' | cut -d= -f2)
  TMP=$(dumpsys battery 2>/dev/null | grep -m1 -oE 'temperature: [0-9]+' | cut -d' ' -f2)
  SAVER=$(settings get global low_power 2>/dev/null)

  # location quality: which provider gave the last fix, how accurate, how old.
  # A "network" provider or an accuracy worse than ~50m means Maps cannot
  # place or orient the car, which is what makes the map look frozen.
  L=$(dumpsys location 2>/dev/null | grep -m1 "last location=Location\[")
  LP=$(echo "$L" | sed -n 's/.*Location\[\([a-z]*\) .*/\1/p')
  LA=$(echo "$L" | grep -oE 'hAcc=[0-9.]+' | cut -d= -f2)
  LT=$(echo "$L" | grep -oE 'et=\+[0-9dhms]+' | cut -d= -f2)

  echo "$TS|$PROJ|${GH:-none}|${HP:-none}|$BT|$SCR|$TMP|$SAVER|${LP:-none}|${LA:-none}|${LT:-none}" >> $OUT/phone.csv
  sleep 5
done
