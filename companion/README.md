# Thar Deck companion app

A small Android app that presses one button for you: Android Auto's head unit
server toggle, keyed to the car's Bluetooth. It also carries Thar Deck Wave's
gesture commands to the phone's music; see "Media relay" below.

## Why this exists

Since Android Auto 17.3, the only wireless projection path that still works is
Android Auto's own head unit server, a developer-menu toggle on the phone. Two
things make that toggle a daily annoyance:

1. **It has to be pressed by hand.** The menu item lives inside Android Auto's
   own process and is not exported, so nothing outside can call it. Every
   intent, service and broadcast route was tried and none opens the port. The
   one lever that exists is the tap.
2. **It dies at unpredictable times.** The server does not simply last until the
   next reboot. It dies with Android Auto's background process, which Android
   recycles on its own. Measured on the build phone: the phone was up 16 days
   and the Android Auto process only hours old, which is exactly when the head
   unit stopped connecting.

So the server needs starting again at times you cannot predict, and only the
tap can do it. This app does the tap, in the car, and undoes it when you leave.

## What it does

```
car Bluetooth connects    ->  make sure the head unit server is running
car Bluetooth disconnects ->  make sure it is stopped
```

Keying on the **car's Bluetooth** is the point. The hotspot is not a "driving"
signal: the tablet is tethered to the same phone for desk work too, so starting
projection whenever the hotspot is up would fire at the desk. Bluetooth to the
car audio is present when driving and absent otherwise, so stopping the server
on disconnect means a later desk tether has nothing to connect to.

It reads Android Auto's own menu to know the state. The label is
"Start head unit server" when the server is down and "Stop head unit server"
when it is up, so the app acts only when the state actually needs to change and
re-reads the label to confirm the toggle flipped. A loopback port probe was
tried first and is unreliable on this phone (cross-UID loopback to 5277 is
dropped even from a shell), which is why the menu label is the signal.

## How the tap is done, and the privacy boundary

An accessibility service performs the tap, and it is **scoped to Android Auto's
package alone** (`android:packageNames="com.google.android.projection.gearhead"`
in `res/xml/tap_service.xml`). It can read and click nothing else on the device.

That scoping is the reason to prefer this over a generic automation app. A
MacroDroid or Tasker macro does the same job in fifteen minutes, but it grants
an accessibility service that can see **every** screen. This one sees exactly
one app, and it only acts inside a short window that the Bluetooth watcher opens
with an explicit direction. Outside that window it stands down.

## The lock screen

The settings UI cannot be driven behind a secure lock screen, so this build
runs in **notification mode**:

- Phone unlocked when the car connects: it acts silently.
- Phone locked: it posts a single notification, and one tap starts the server.
- For a stop while locked, it waits and stops on your next unlock, if the car
  is still gone.

There is no attempt to bypass the lock.

## Build

No Gradle. Needs the Android SDK build-tools 36 and platform android-36, a JDK,
and the standard debug keystore at `~/.android/debug.keystore`.

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
powershell -ExecutionPolicy Bypass -File build.ps1 -Install   # also adb install -r
```

The pipeline is aapt2 compile, aapt2 link, javac, d8, add the dex, zipalign,
apksigner. It produces a signed debug APK of about 29 KB.

## Install and set up

```bash
adb install -r -g build/thardeck.apk
# enable the accessibility service
adb shell settings put secure enabled_accessibility_services \
    com.abhi.thardeck/com.abhi.thardeck.TapService
adb shell settings put secure accessibility_enabled 1
# let it run in the background
adb shell dumpsys deviceidle whitelist +com.abhi.thardeck
adb shell cmd appops set com.abhi.thardeck RUN_ANY_IN_BACKGROUND allow
# launch once, then pick the car in the app, or set it by name:
adb shell am start -n com.abhi.thardeck/.MainActivity
adb shell am broadcast -n com.abhi.thardeck/.ControlReceiver \
    -a com.abhi.thardeck.SET_CAR --es name "'Auto 12'"
```

The car's Bluetooth address is chosen from bonded devices and stored on the
device only. It is never hardcoded: an address is personal, and it differs on
every phone.

## Manual controls and adb

The notification carries a pause toggle, and the app screen exposes start, stop
and the device picker. From adb, target the receiver by component, because
Samsung blocks background **implicit** broadcasts to manifest receivers (the
notification and the app UI use explicit intents, so this only affects adb):

```bash
adb shell am broadcast -n com.abhi.thardeck/.ControlReceiver -a com.abhi.thardeck.START
adb shell am broadcast -n com.abhi.thardeck/.ControlReceiver -a com.abhi.thardeck.STOP
adb shell am broadcast -n com.abhi.thardeck/.ControlReceiver -a com.abhi.thardeck.TOGGLE
adb shell am broadcast -n com.abhi.thardeck/.ControlReceiver -a com.abhi.thardeck.SET_CAR --es name "'Auto 12'"
adb shell am broadcast -n com.abhi.thardeck/.ControlReceiver -a com.abhi.thardeck.SET_TOKEN --es token <value>
```

## Media relay

Thar Deck Wave, the hand-gesture app on the dashboard tablet, controls music
that plays from this phone, so its commands are relayed here. The foreground
service listens on UDP port 5299 for one ASCII line per datagram:

```
TD1 <token> <CMD>      CMD: PING, VOL_UP, VOL_DOWN, PLAY_PAUSE, NEXT, PREV
```

- `VOL_UP` and `VOL_DOWN` step the phone's music volume, with the system volume
  panel shown.
- `PLAY_PAUSE`, `NEXT` and `PREV` are sent as media key presses to whichever
  media session is active.
- `PING` is answered to the sender with `TD1 PONG <phone model>`, which is how
  the tablet finds the phone, by its gateway address on the phone's hotspot or
  by subnet broadcast on other Wi-Fi.
- A wrong token or a malformed line is dropped without a reply.
- If a network change breaks the socket, the relay closes it, waits a second
  and binds again.

The relay runs whenever the service runs. The pause switch covers the car
watcher only. Each applied command is logged at tag `THARDECK` as
`relay: <CMD> from <sender>`; the token is never logged. The status screen and
the notification show the relay state.

The token defaults to `thardeck`, the same default as the Wave app. Change it
on both sides together:

```bash
adb shell am broadcast -n com.abhi.thardeck/.ControlReceiver -a com.abhi.thardeck.SET_TOKEN --es token <value>
```

A token must be a single word of printable ASCII, because the protocol is space
separated.

Verified on the build phone (model SM-S938B) over Wi-Fi, with UDP
datagrams sent by a Python script on a PC on the same subnet:

| Case | Result |
|---|---|
| Service start | log `relay: listening on 5299`; the notification reads "relay listening on 5299" |
| `VOL_UP` | STREAM_MUSIC 6 to 7 of 15 (`cmd media_session volume --stream 3 --get`), log `relay: VOL_UP from <pc>` |
| `VOL_DOWN` | STREAM_MUSIC 7 to 6 of 15, log `relay: VOL_DOWN from <pc>` |
| `PING` | `TD1 PONG SM-S938B` received on the sending socket from port 5299, within the 2 s timeout |
| `PLAY_PAUSE` | log line written; no media session was active beforehand, and Android routed the key to the last media app, which started playing. A second `PLAY_PAUSE` paused it |
| Wrong token, `VOL_UP` | no reply, volume unchanged at 6, no log line, status screen "Relay datagrams dropped: 1" |

Not yet verified: `NEXT` and `PREV`, broadcast discovery, the phone's own
hotspot, and the reopen after a real network change.

## Verified

On the build hardware, phone unlocked, driven by the manual controls that share
the exact code path the Bluetooth trigger uses:

| Case | Result |
|---|---|
| Server up, stop requested | menu read, "Stop" tapped, flip confirmed, port down in about 2 s |
| Server down, start requested | "Start" tapped, flip confirmed, port up in about 2 s |
| Server up, start requested again | menu read as already running, nothing tapped |

The Bluetooth trigger itself is confirmed on a drive, since it needs the car's
audio to connect and disconnect for real.

Fix after the first drive: a deferred stop right after unlock clicked "More
options" seven times in 700 ms because the popup had not reached the tree yet,
and each click closed the last. The tapper now searches every Android Auto
window, waits 800 ms between overflow clicks, caps them at 3 per action, and
the service relaunches settings once if the tapper has not acted after 3 s.
This build installs and the tapper connects; the stop and start taps have not
yet been re-run on it, because the phone was locked at test time.

## Limitations

- Notification mode only; it does not act behind a secure lock screen.
- It briefly opens Android Auto settings on the phone to read and set the state,
  then returns to the home screen. That is a short flash on the phone screen,
  not the tablet.
- Verified on one phone. Menu labels are read in English; a different system
  language would need the labels adjusted.
