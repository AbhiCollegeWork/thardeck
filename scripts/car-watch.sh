#!/system/bin/sh
# Thar Deck: head unit server lifetime watcher.
#
# Answers one question: how often does Android Auto's :car process get recycled,
# and does the head unit server (TCP 5277) die with it?
#
# Deliberately cheap. Two shell reads every 5 minutes, no dumpsys, no logcat.
# The logger that overheated this phone ran five dumpsys calls every five seconds;
# this is roughly three thousand times less work. Leave it running for days.
#
#   adb push car-watch.sh /data/local/tmp/
#   adb shell "chmod +x /data/local/tmp/car-watch.sh; nohup /data/local/tmp/car-watch.sh >/dev/null 2>&1 &"
#   adb shell "cat /sdcard/car-watch.log"      # read it back
#   adb shell "pkill -f car-watch.sh"          # stop it

OUT=/sdcard/car-watch.log
INTERVAL=300

[ -f "$OUT" ] || echo "timestamp|uptime_s|car_pid|car_age|server_5277|note" >> "$OUT"

LAST_PID=""
while true; do
    TS=$(date +%Y-%m-%d_%H:%M:%S)
    UP=$(cut -d. -f1 /proc/uptime)

    # user 0's :car only. Secure Folder runs its own; ignore it.
    LINE=$(ps -A -o PID,USER,ETIME,NAME 2>/dev/null | grep 'gearhead:car' | grep 'u0_' | head -1)
    PID=$(echo "$LINE" | awk '{print $1}')
    AGE=$(echo "$LINE" | awk '{print $3}')
    [ -z "$PID" ] && PID=dead && AGE=-

    # 149D is 5277 in hex, state 0A is LISTEN
    if cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '$4=="0A"' | grep -qi 149D; then
        SRV=up
    else
        SRV=DOWN
    fi

    NOTE=""
    if [ -n "$LAST_PID" ] && [ "$PID" != "$LAST_PID" ]; then
        NOTE="RECYCLED was=$LAST_PID"
    fi
    LAST_PID="$PID"

    echo "$TS|$UP|$PID|$AGE|$SRV|$NOTE" >> "$OUT"
    sleep $INTERVAL
done
