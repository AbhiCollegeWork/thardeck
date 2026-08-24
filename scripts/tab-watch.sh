#!/system/bin/sh
# tab-watch - resilient capture for the Thar Deck receiver.
#
# The previous setup used an unsupervised `logcat -b all`, which the system
# reaped on 19 Aug without a word, so the freezes on 21-22 Aug were never
# recorded. This version:
#   1. filters to the tags that matter, so it writes ~50x less and survives
#   2. supervises itself, restarting logcat within 30s if it is killed
#   3. records every session transition with a timestamp, so a freeze is
#      visible even if logcat is lost again
OUT=/sdcard/Download/aa-diag
mkdir -p $OUT

start_logcat() {
  logcat -c 2>/dev/null
  logcat -v threadtime -r 8192 -n 12 -f $OUT/logcat.txt \
    OPENHU:V HUREV:V AapService:V ActivityManager:I WifiService:I \
    wpa_supplicant:I MediaCodec:W ACodec:W CCodec:W lowmemorykiller:I \
    lmkd:I ThermalService:I SurfaceFlinger:W BluetoothAdapter:I \
    *:S >/dev/null 2>&1 &
}

sessions() {
  cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '$2 ~ /14A8$/ && $4 == "01"' | wc -l
}

echo "tab-watch started $(date +%Y-%m-%d_%H:%M:%S)" >> $OUT/events.log
start_logcat
LAST=$(sessions)

while true; do
  # --- supervise logcat -----------------------------------------------------
  ALIVE=$(ps -A 2>/dev/null | grep -c "[l]ogcat")
  if [ "$ALIVE" -eq 0 ]; then
    echo "$(date +%Y-%m-%d_%H:%M:%S)|WATCHDOG|logcat was dead, restarting" >> $OUT/events.log
    start_logcat
  fi

  # --- record session transitions ------------------------------------------
  NOW=$(sessions)
  if [ "$NOW" != "$LAST" ]; then
    W=$(dumpsys wifi 2>/dev/null | grep -m1 mWifiInfo)
    RSSI=$(echo "$W" | grep -oE 'RSSI: -?[0-9]+' | head -1)
    RX=$(echo "$W" | grep -oE 'Rx Link speed: [0-9]+Mbps' | head -1)
    SCR=$(dumpsys power 2>/dev/null | grep -m1 -oE 'mWakefulness=[A-Za-z]+')
    PROC=$(pidof com.andrerinas.headunitrevived 2>/dev/null)
    if [ "$NOW" -gt "$LAST" ]; then EV=SESSION_UP; else EV=SESSION_DOWN; fi
    echo "$(date +%Y-%m-%d_%H:%M:%S)|$EV|count=$NOW|$RSSI|$RX|$SCR|apppid=$PROC" >> $OUT/events.log
    LAST=$NOW
  fi
  sleep 5
done
