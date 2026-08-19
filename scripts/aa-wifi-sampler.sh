#!/system/bin/sh
# aa-wifi-sampler - logs Wi-Fi link quality + whether an Android Auto session is live.
# Port 5288 = hex 14A8. State 01 = ESTABLISHED. uid-independent so it survives app reinstalls.
OUT=/sdcard/Download/aa-diag
mkdir -p $OUT
echo "timestamp|ssid|rssi|link_speed|rx_speed|frequency|aa_session" >> $OUT/wifi.csv
while true; do
  TS=$(date +%Y-%m-%d_%H:%M:%S)
  W=$(dumpsys wifi 2>/dev/null | grep -m1 mWifiInfo)
  SSID=$(echo "$W" | grep -oE 'SSID: "[^"]*"' | head -1)
  RSSI=$(echo "$W" | grep -oE 'RSSI: -?[0-9]+' | head -1)
  LINK=$(echo "$W" | grep -oE 'Link speed: [0-9]+Mbps' | head -1)
  RX=$(echo "$W" | grep -oE 'Rx Link speed: [0-9]+Mbps' | head -1)
  FREQ=$(echo "$W" | grep -oE 'Frequency: [0-9]+MHz' | head -1)
  EST=$(cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '$2 ~ /14A8$/ && $4 == "01"' | wc -l)
  echo "$TS|$SSID|$RSSI|$LINK|$RX|$FREQ|session=$EST" >> $OUT/wifi.csv
  sleep 10
done
