#!/system/bin/sh
# phone-watch - source-side capture, the half that was never recorded before.
#
# A projection session can be torn down from either end. The tablet sampler
# proved the radio link was healthy through all five freezes, so the cause is
# above the network layer - and the phone is the more likely end, because it
# runs Android Auto, the Wireless Helper, the hotspot and the Bluetooth link
# to the car audio dongle, any of which can end a session.
#
# Records one row every 5 seconds:
#   timestamp | projecting | gearhead pid | helper pid | BT links | hotspot clients | screen | battery temp
OUT=/sdcard/Download/aa-diag
mkdir -p $OUT
echo "timestamp|projecting|gearhead|helper|bt_acl|ap_clients|screen|batt_temp" >> $OUT/phone.csv

start_logcat() {
  logcat -c 2>/dev/null
  logcat -v threadtime -r 8192 -n 10 -f $OUT/phone-logcat.txt \
    "GH.*:V" CAR.SETUP:V CarService:V ActivityManager:I WifiService:I \
    SoftApManager:I WifiHotspot:I bt_stack:W BluetoothAdapter:I \
    A2dpService:I ThermalService:I *:S >/dev/null 2>&1 &
}

start_logcat
echo "phone-watch started $(date +%Y-%m-%d_%H:%M:%S)" >> $OUT/phone-events.log

while true; do
  ALIVE=$(ps -A 2>/dev/null | grep -c "[l]ogcat")
  if [ "$ALIVE" -eq 0 ]; then
    echo "$(date +%Y-%m-%d_%H:%M:%S)|WATCHDOG|logcat restarted" >> $OUT/phone-events.log
    start_logcat
  fi

  TS=$(date +%Y-%m-%d_%H:%M:%S)
  # an outbound connection to a head unit on 5288 means we are projecting
  PROJ=$(cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '$3 ~ /14A8$/ && $4 == "01"' | wc -l)
  GH=$(pidof com.google.android.projection.gearhead 2>/dev/null | awk '{print $1}')
  HP=$(pidof com.andrerinas.wirelesshelper 2>/dev/null | awk '{print $1}')
  BT=$(dumpsys bluetooth_manager 2>/dev/null | grep -c "ACL BR/EDR:Y")
  AP=$(dumpsys wifi 2>/dev/null | grep -ciE "^ *client|connected station" )
  SCR=$(dumpsys power 2>/dev/null | grep -m1 -oE 'mWakefulness=[A-Za-z]+' | cut -d= -f2)
  TMP=$(dumpsys battery 2>/dev/null | grep -m1 -oE 'temperature: [0-9]+' | cut -d' ' -f2)
  echo "$TS|$PROJ|${GH:-dead}|${HP:-dead}|$BT|$AP|$SCR|$TMP" >> $OUT/phone.csv
  sleep 5
done
