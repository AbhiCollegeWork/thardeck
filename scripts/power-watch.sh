#!/system/bin/sh
# power-watch - energy telemetry, runs on both devices.
#
# Neither device exposes an instantaneous current sensor to the shell, so this
# logs the fuel gauge's coulomb counter instead. Differencing that over time
# gives exact charge moved in mAh, which is better than sampling current
# anyway: it is the integrated truth rather than a noisy instant.
#
# Multiply by voltage and you have energy, and energy over time is the average
# power the system is actually drawing.
#
#   ROLE=tab   sh power-watch.sh     (receiver: listens on 5288)
#   ROLE=phone sh power-watch.sh     (source: connects out to 5288)
#
# Row every 30s:
#  timestamp|level|charge_uAh|voltage_mV|temp_dC|status|plugged|projecting|screen
OUT=/sdcard/Download/aa-diag
ROLE=${ROLE:-tab}
mkdir -p $OUT
F=$OUT/power-$ROLE.csv
[ -f "$F" ] || echo "timestamp|level|charge_uAh|voltage_mV|temp_dC|status|plugged|projecting|screen" >> $F

while true; do
  TS=$(date +%Y-%m-%d_%H:%M:%S)
  B=$(dumpsys battery 2>/dev/null)

  # head -1 matters: some builds print the counter on more than one line
  LEVEL=$(echo "$B"  | grep -m1 "  level:"          | grep -oE '[0-9]+' | head -1)
  CHG=$(echo "$B"    | grep -m1 -i "Charge counter" | grep -oE '[0-9]+' | head -1)
  VOLT=$(echo "$B"   | grep -m1 "  voltage:"        | grep -oE '[0-9]+' | head -1)
  TEMP=$(echo "$B"   | grep -m1 "  temperature:"    | grep -oE '[0-9]+' | head -1)
  STAT=$(echo "$B"   | grep -m1 "  status:"         | grep -oE '[0-9]+' | head -1)
  # 1 none, 2 charging, 3 discharging, 4 not charging, 5 full
  PLUG=$(echo "$B"   | grep -m1 -iE "AC powered: true|USB powered: true" >/dev/null && echo 1 || echo 0)

  if [ "$ROLE" = "phone" ]; then
    PROJ=$(cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '$3 ~ /14A8$/ && $4 == "01"' | wc -l)
  else
    PROJ=$(cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '$2 ~ /14A8$/ && $4 == "01"' | wc -l)
  fi

  SCR=$(dumpsys power 2>/dev/null | grep -m1 -oE 'mWakefulness=[A-Za-z]+' | cut -d= -f2)

  echo "$TS|${LEVEL:-}|${CHG:-}|${VOLT:-}|${TEMP:-}|${STAT:-}|$PLUG|$PROJ|${SCR:-}" >> $F
  sleep 30
done
