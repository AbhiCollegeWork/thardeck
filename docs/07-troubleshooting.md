# 07 - Troubleshooting

Every failure hit during this build, what caused it, and how it was fixed - including the approaches that turned out to be dead ends, so you can skip them.

## Quick index

| Symptom | Section |
|---|---|
| Phone never finds the tablet | [§1](#1-the-phone-never-finds-the-tablet) |
| Screen corrupts into macroblocks while driving | [§2](#2-video-corrupts-into-macroblocks) |
| Audio plays from the tablet instead of the car | [§3](#3-audio-comes-out-of-the-tablet) |
| Picture is portrait, cropped, or clipped | [§4](#4-portrait-cropped-or-clipped-picture) |
| Everything too small to touch while driving | [§5](#5-ui-too-small) |
| App launches itself when you are not in the car | [§6](#6-the-app-launches-itself) |
| Tablet battery drains when parked | [§7](#7-battery-drains-when-parked) |
| Settings will not stick | [§8](#8-settings-revert-silently) |
| "Self Mode" hangs at the animation | [§9](#9-self-mode-hangs-at-the-animation) |
| adb worked yesterday, refuses today | [§10](#10-adb-stops-connecting-after-about-a-week) |
| Screen freezes mid-drive, recovers by itself | [§11](#11-screen-freezes-mid-drive-then-recovers) |
| Car icon or heading arrow missing, map stops following | [§12](#12-car-icon-and-heading-arrow-missing-map-stops-following) |
| Nothing connects at all after an Android Auto update | [§13](#13-nothing-connects-after-an-android-auto-update) |
| Projection dies when a Bluetooth keyboard connects | [§14](#14-projection-dies-when-a-bluetooth-keyboard-connects) |
| Projection starts by itself when you are not driving | [§15](#15-projection-starts-when-you-are-not-driving) |
| Noise or hum in the speakers | [04 - Audio Chain](04-audio-chain.md#the-noise-problem) |
| Bass thin after fitting an isolator | [04 - Audio Chain](04-audio-chain.md#recovering-the-bass) |

---

## 1. The phone never finds the tablet

**Symptom** - helper sits at `SEARCHING` indefinitely. No error, no timeout. Both devices on the same network. Everything looks correct.

**Root cause** - **discovery protocol mismatch.** The receiver defaults to *Google Nearby (Beta)* while the helper searches via Shared Wi-Fi / Phone Hotspot. Both ends discover, neither advertises, and they deadlock forever.

Tablet log during the deadlock:
```
NearbyManager: Starting Nearby (Discoverer only)
```

**Fix** - tablet → Settings → ADVANCED → Wireless Connection → **Helper Connection Strategy = `Phone Hotspot (Host)`**.

**If that does not fix it**, work down this list:

| Check | Command / action |
|---|---|
| Is the receiver actually listening? | `adb shell "cat /proc/net/tcp /proc/net/tcp6" \| grep 14A8` |
| Screen asleep? | The tablet drops off Wi-Fi entirely when it sleeps - port, adb and mDNS all vanish together. **Keep it awake during any test.** |
| Permissions granted? | Location, Nearby devices, Bluetooth - all required for discovery |
| Battery optimisation? | Set the app to **Unrestricted** |
| Version current? | 3.2.0 fixed multiple Wi-Fi Direct and reconnect bugs. Do not debug on an older build. |
| Helper pointed at the right device? | Exactly one Bluetooth device selected, and it must be the car one |

---

## 2. Video corrupts into macroblocks

**Symptom** - the picture smears into blocky garbage, usually within the first minutes of a drive, then clears by itself later. **This is a safety problem** - the screen becomes unreadable at speed.

**What it is not:** not a decoder bug, not weak signal, not too high a resolution.

**Root cause** - **STA+AP co-channel collision.** The phone serves the hotspot on the *same channel* it is using for its own Wi-Fi connection, because single-radio concurrency forces the SoftAP onto the station's channel. Near home, the router and all household traffic collide with the video stream.

The diagnostic signature:
```
RSSI:          -25 to -32 dBm   <- excellent
Link speed:    104-144 Mbps
Rx Link speed: 1-6 Mbps         <- collapsed
```

**Strong signal with collapsed throughput is contention, never range.** Confirmed by measuring both interfaces at once and finding identical frequencies - and by this line, the phone losing beacons from its own hotspot:
```
determineBeaconLossDisconnection: DISCONN bssid=<phone's own SoftAP> rssi=-94/-88
```

It self-heals as you drive away because the station link drops and the radio dedicates itself to the AP. Which is exactly why it looked like "a start-of-drive problem" and never reproduced on the bench.

**Fix** - phone → Settings → Mobile Hotspot → Configure → **Band = 2.4 GHz / "Compatibility"**.

> **The band change is not applied while the station holds a 5 GHz link.** Cycle the hotspot off and on, then verify the two are in different bands:
> ```bash
> adb shell dumpsys wifi | grep -i frequency
> ```

**Measured result:** 866 Mbps both directions, session in 4 seconds, 50 FPS at 5 ms frame time.

**If corruption persists after separating the bands:** drop projection resolution from 1080p to 720p (Settings → search `resolution`). Halves the bitrate and makes the stream far more loss-tolerant.

**Do not chase:** resolution was verified as 1080p, not 1440p, so bitrate was never the amplifier. The HEVC decoder is present and healthy. Neither is your problem.

---

## 3. Audio comes out of the tablet

**Symptom** - projection works, but music and navigation come from the tablet speakers and the car goes silent.

**Root cause** - the receiver advertises audio sinks during handshake, so Android Auto routes audio to it.

**Fix** - tablet → Settings → ADVANCED → search `audio` → **Audio Sink = OFF** → Save → **reconnect** (it takes effect at the next handshake).

**Verify on the phone:**
```bash
adb -s <PHONE> shell dumpsys audio | grep -iE "device|focus"
```
Expected: Android Auto requests audio focus then immediately abandons it, and **no** Android Auto or BUS audio device appears in the routing table.

Display, touch and media controls all keep working - the system sink (`AU2`) is retained unconditionally to hold the connection open. [Protocol detail](05-protocol-notes.md#2-audio-sinks-are-advertised-selectively).

---

## 4. Portrait, cropped, or clipped picture

**Symptom A - session starts portrait.** **Symptom B - edges cut off, UI clipped.** **Symptom C - rotating mid-drive breaks the picture.**

**Root cause** - geometry is negotiated **once**, at handshake, and cannot be renegotiated. Whatever the session starts in is baked in.

For symptom B specifically: cropping means **stale session geometry** - something changed after the handshake. A clean reconnect renegotiates and restores the full layout.

**Fix - both layers are required:**

```bash
# Layer 2: Android 12L+ tablets ignore app orientation requests entirely
adb shell cmd window set-ignore-orientation-request false
```
plus **Settings → BASIC → search `orient` → Screen orientation = Landscape (0°)**.

Layer 1 alone does nothing on a modern tablet. That is what makes this so confusing to debug - the setting is right there, set correctly, and ignored.

**Side effect to know about:** with the override false, all apps regain the ability to lock orientation, so portrait-only apps may force the tablet into portrait. Revert with `set-ignore-orientation-request true`, or instead lock the whole system:
```bash
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
```

**Persistence:** the override is stored in `/data/system/display_settings.xml`, unreadable without root, so survival across reboot is unconfirmed. If orientation regresses after a restart, re-run the command.

---

## 5. UI too small

**Symptom** - everything, especially the on-screen keyboard, is too small to hit reliably while driving.

**Root cause** - the default 172 DPI yields ~1515 dp of width. Real car head units are 1000-1200 dp, so the UI renders about 30% smaller than designed.

**Fix** - Settings → BASIC → search `density` → **Pixel density (DPI) = `L` (218)** → Save. About 27% larger touch targets. Go to XL if needed. Takes effect at the next connection.

---

## 6. The app launches itself

**Symptom** - the head unit app opens on its own while you are using the tablet for something else.

**Root cause** - *Auto-start on Bluetooth* pointing at a device that is **not** car-exclusive. In this build it was aimed at the phone, so any momentary phone-tablet Bluetooth contact launched it:

```
OPENHU: AutoStartReceiver.onReceive | BT Device connected: <phone>
OPENHU: MATCH! Starting AapService via Bluetooth Auto-start
```

**Fix** - Settings → Auto-start settings → Auto-start on Bluetooth → **Remove** (use the picker's Remove button; you cannot untick it).

Audit all three auto-start paths while you are there:

| Setting | Safe value |
|---|---|
| Start on boot | OFF |
| Start on screen on | **OFF** - this one fires on every normal use of the tablet |
| Auto-start on Bluetooth | Not set, **or** a device that exists only in your car |

---

## 7. Battery drains when parked

**Symptom** - the tablet loses charge overnight with the app merely armed.

**Root cause, and a correction to a widely repeated claim.** "Background app with no wakelocks = free" is **wrong**. Measured on an armed, backgrounded receiver:

```
oom_score_adj:   0       <- treated as foreground-important
wakelocks:       0       <- looks innocent
wakeupap events: 1177    <- device wakeups + Wi-Fi radio spin-ups
```

Checking only the wakelock list gives a false all-clear.

**Fix** - **press the tablet's power button when you park.** Screen off gives:
```
mWakefulness=Dozing
0 wakelocks
```
while the receiver stays armed for the next drive. Zero drain *and* zero taps.

**Also check** whether anything set these while debugging - they will hold the screen on forever:
```bash
adb shell settings get global stay_on_while_plugged_in   # want 0
adb shell settings get system screen_off_timeout         # want your normal value
```

> These two were set during this build to keep a device reachable, and quietly ruined its daily use until the owner noticed and reported it. **If you change system-wide settings on a device someone uses daily, say so at the time and offer the revert up front.** Debug convenience is not worth someone else's tablet behaving strangely for a week.

**Do not diagnose with the app in the foreground** - a foregrounded receiver deliberately holds `SCREEN_BRIGHT_WAKE_LOCK` to keep the display alive. That is correct behaviour, not the bug.

---

## 8. Settings revert silently

**Symptom** - you change a setting, and later it is back to its old value. No error, no prompt.

**Root cause** - **each settings sub-screen has its own Save**, and navigating away discards unsaved changes.

**Fix - the procedure that works:**
1. Change the setting
2. Tap **Save on that same screen**
3. Back out, re-enter, confirm
4. Force-stop and re-check - preferences are only re-read from disk on a cold start
```bash
adb shell am force-stop com.andrerinas.headunitrevived
adb shell am start -n com.andrerinas.headunitrevived/com.andrerinas.openheadunit.main.MainActivity
```

**Related trap:** the settings search box does **not** clear between searches. New terms append to old ones, producing strings like `auto-startdisconnect` and an empty result list - which reads exactly like "this setting does not exist". **Tap the X first, every time.**

---

## 9. Self Mode hangs at the animation

**Symptom** - the receiver's "Self Mode" spins forever.

**Root cause** - Self Mode requires **Android Auto installed on the tablet**, which it is not, and should not be:
```
Activity launch failed (Unable to find explicit activity class
{com.google.android.projection.gearhead/...WirelessStartupActivity})
```

**Fix** - **ignore Self Mode entirely.** It is irrelevant to using the tablet as a receiver. Do not install Android Auto on the tablet to satisfy it.

---

## 10. adb stops connecting after about a week

**Symptom** - wireless adb worked for days, now `adb connect` fails. The port is clearly open.

**Root cause** - **adb pairing authorisation expires after roughly 7 days.** The distinctive signature: the port is reachable but adb is refused, meaning TLS/auth rejection rather than a network problem.

```powershell
Test-NetConnection <TABLET_IP> -Port <PORT>   # succeeds
adb connect <TABLET_IP>:<PORT>                # fails
```

**Fix** - re-pair: Developer options → Wireless debugging → **Pair device with pairing code**.

**Prevent** - enable **"Disable adb authorisation timeout"** in Developer options.

**Related:** duplicate transports cause `more than one device/emulator`. Drop the stale one, keep the mDNS-named transport:
```bash
adb disconnect <ip:port>
```

---

## 11. Screen freezes mid-drive, then recovers

**Symptom** - the picture stops dead on one frame, stays frozen for anywhere between 30 seconds and 4 minutes, then comes back on its own. No corruption, no macroblocks, just a still image. Audio keeps playing normally throughout, because audio never crosses the Wi-Fi link.

> **Cause found.** This section originally recorded an unexplained fault. Two-ended instrumentation identified it: **the receiver app is being killed by the system while it is projecting.** The evidence and the fix are below.

**This is a different fault from [§2](#2-video-corrupts-into-macroblocks), and the two are easy to confuse.** Corruption is a damaged stream. A freeze is no stream at all. They have opposite causes and opposite fixes, so establish which one you have before doing anything.

### Telling them apart

| | Corruption | Freeze |
|---|---|---|
| Picture | Smeared, blocky, moving | Static, one clean frame |
| Session on port 5288 | Stays ESTABLISHED | Drops to zero |
| Rx throughput | Collapses | Stays healthy |
| Cause | Radio contention | Session torn down above the network layer |

### What the data showed

Across nine days and twelve drives, five mid-drive freezes were captured. In every one the radio link was **perfect** while the session was down:

```
19:54:32   session=1   RSSI -38   Rx 585 Mbps    <- last frame before the freeze
19:54:42   session=0   RSSI -36   Rx 433 Mbps    <- screen frozen, link fine
19:56:45   session=0   RSSI -37   Rx 468 Mbps    <- still frozen, still fine
19:58:38   session=1   RSSI -32   Rx 526 Mbps    <- recovered by itself
```

RSSI stayed between -34 and -44 dBm and throughput between 433 and 866 Mbps for the entire outage. The tablet never left the hotspot, the frequency never changed, and no other network appeared.

**That rules out the whole network layer.** Signal, range, channel contention, roaming and band selection are all excluded by the numbers. The TCP session died while the pipe underneath it was wide open.

### The cause

The tablet-side watcher records the receiver's process ID alongside every session transition. That single field settled it:

```
20:43:44  SESSION_UP    apppid=12189
20:43:55  SESSION_DOWN  apppid=13850     <- different process
21:42:13  SESSION_DOWN  apppid=          <- no process at all
21:43:15  SESSION_UP    apppid=5724
21:45:39  SESSION_DOWN  apppid=          <- gone again
```

**The process ID changes at every single drop, and is sometimes empty.** The app is not losing its socket. It is being killed and restarted, and the session dies with it.

Confirmed directly in the system log:

```
ActivityManager: Process com.andrerinas.headunitrevived (pid 23445) has died: fg SVC
ActivityManager: Killing 13331:com.andrerinas.headunitrevived (adj 850): Chimera #0
```

`fg SVC` means it died while running as a **foreground service**, which is what it does while projecting. One captured log file recorded **16 restarts**. On the worst evening there were nine session drops in twenty-seven minutes.

### The fix

Being exempt from Doze is not enough. Vendor builds run additional memory and battery management that reclaims processes independently, and the receiver was in the default app-standby bucket.

```bash
# exempt the receiver from app standby entirely
adb shell am set-standby-bucket com.andrerinas.headunitrevived active
adb shell dumpsys deviceidle whitelist +com.andrerinas.headunitrevived

# verify: bucket 5 is EXEMPTED, 10 is ACTIVE, higher numbers are throttled
adb shell am get-standby-bucket com.andrerinas.headunitrevived
```

Also do these in the tablet's own settings, because they are separate mechanisms from the ones above:

| Setting | Value |
|---|---|
| Battery for the receiver app | Unrestricted |
| Adaptive battery | Off, or add the app to the never-sleeping list |
| Put unused apps to sleep | Off, or exempt the app |

**Reduce memory pressure too.** The reference tablet runs over 800 tasks with roughly 2.5 GB of swap in use. The fewer apps competing, the less often anything gets reclaimed.

### Other candidates, now excluded or demoted

Something above the transport ended the session. For completeness, the alternatives that were considered:

| Candidate | Why it fits | How to confirm |
|---|---|---|
| The receiver app was killed or restarted | Vendor process management reaps background processes aggressively; the same tablet silently killed an unsupervised `logcat` | App pid changes across the freeze |
| Android Auto ended projection | It owns the session and can tear it down for its own reasons | `GH.*` tags on the phone at the freeze timestamp |
| The Helper stopped and restarted it | With *Stop on BT disconnect* enabled, a momentary Bluetooth blip to the car audio device ends projection, and auto-reconnect brings it back 30 to 60 seconds later, which matches the observed recovery times | Bluetooth ACL count on the phone at the freeze timestamp |
| Thermal throttling stalling the encoder or decoder | A tablet on a windscreen under load | Battery and thermal readings across the freeze |

> **Do not guess between these.** Each has a different fix, and applying the wrong one hides the fault instead of removing it. The [capture tooling](06-diagnostics.md#supervised-capture) records exactly the signals that separate them.

### The instrumentation gap that made this hard

The freezes could not be attributed because **the log capture had died two days earlier and nothing noticed.** An unsupervised `logcat` was reaped by the system, leaving only the Wi-Fi sampler running. The sampler was enough to exclude the network, which is genuinely useful, but not enough to identify the cause.

Two lessons, both now built into the tooling:

1. **Supervise anything long-running.** `scripts/tab-watch.sh` restarts logcat within 30 seconds of it dying and records that it happened.
2. **Capture both ends.** Only the tablet was instrumented. A session has two ends and either can end it, so `scripts/phone-watch.sh` now records the source side: whether Android Auto is projecting, whether its process is alive, the Bluetooth link state, hotspot clients, and temperature.


---

## 12. Car icon and heading arrow missing, map stops following

**Symptom** - the map is drawn and the interface responds, but the blue car icon and the direction arrow are absent, and the map stops following the vehicle. It looks like a frozen screen and is easily mistaken for [§11](#11-screen-freezes-mid-drive-then-recovers), but it is a completely different fault.

### Telling it apart from a real freeze

| | Session freeze (§11) | Map not updating (§12) |
|---|---|---|
| Car icon | Frozen in place | **Absent entirely** |
| Heading arrow | Frozen | **Absent** |
| Session on port 5288 | Drops to zero | Stays established |
| Receiver process ID | Changes | Unchanged |
| Touch response | Dead | Normal |

If the interface still responds to touch and the session never dropped, the video path is fine and the problem is **location**.

### Cause

**Battery Saver was enabled on the phone.** It throttles location, and Android Auto takes its position from the phone, not from the head unit. The receiver declares only two sensors, `SENSOR_TYPE_DRIVING_STATUS` and `SENSOR_TYPE_NIGHT`, and no position sensor at all, so there is no second source to fall back on.

The measured state during the affected period:

```
low_power                = 1              <- Battery Saver on
location requests        = BALANCED       <- low power, network biased, not high accuracy
last fix                 = network, hAcc=100.0 m, et=+9d22h
```

A hundred-metre network fix is far too coarse for Maps to place a vehicle on a road or derive a heading, so it draws neither. The map keeps its last tiles and simply stops following.

### The fix

**Do not simply turn Battery Saver off.** Hosting the hotspot is a heavy, sustained load, and on a long drive the saver is what keeps the phone alive and cool enough to be useful. Disabling it trades a navigation fault for a battery fault.

Android keeps the saver's *policy* separate from its on/off switch, so location can be exempted while every other restriction stays in force:

```bash
# keep Battery Saver ON, but stop it throttling location
adb shell settings put global battery_saver_constants 'location_mode=0'
adb shell settings put global low_power 1
```

`location_mode=0` is NO_CHANGE: GPS behaves exactly as it would with the saver off, while CPU, background work and sync stay restricted. Verify it is really in force, not just stored:

```bash
adb shell "dumpsys power | grep location_mode"
```

If you would rather disable the saver entirely:

```bash
adb shell settings put global low_power 0

# exempt navigation from battery optimisation so it survives if it is re-enabled
adb shell dumpsys deviceidle whitelist +com.google.android.apps.maps
adb shell dumpsys deviceidle whitelist +com.google.android.projection.gearhead
adb shell am set-standby-bucket com.google.android.apps.maps active
```

Also confirm in the phone's settings that location is set to high accuracy, and that Wi-Fi and Bluetooth scanning for location are enabled.

> **Battery Saver is often switched on automatically at a low battery threshold.** If navigation degrades late in a long drive but is fine at the start, this is very likely the reason. Keeping the phone charged in the car avoids the trigger entirely, and the exemptions above limit the damage if it does fire.

### Confirming it

The phone-side watcher records the location provider, its accuracy and the age of the fix on every row. During a healthy drive expect the provider to be `gps` or `fused` with an accuracy in single-digit metres. A `network` provider, an accuracy near 100, or a fix age that keeps growing all mean location is starved.


---

## 13. Nothing connects after an Android Auto update

**Symptom** - everything worked yesterday. Today the helper app searches forever and the head unit never connects. USB still works.

**Cause** - **Android Auto 17.3 removed the connection intent helper apps used.** It is not configuration, and no setting will bring it back. See [Protocol Notes](05-protocol-notes.md#android-auto-173-removed-the-helper-path) for the evidence and the upstream confirmation.

**The one-command test.** Ask whether the phone is even trying:

```bash
adb -s <PHONE> shell "cat /proc/net/tcp /proc/net/tcp6" | awk '$3 ~ /14A8$/'
```

No rows means the phone was never told where to connect. That excludes your network, your head unit and your settings in a single step, and points squarely at the phone side.

**Fix** - switch to the [head unit server](05-protocol-notes.md#the-head-unit-server-the-path-that-still-works), then **turn off auto-update for Android Auto** so it cannot happen again.

> **Do not try to roll Android Auto back by uninstalling its updates.** On a Samsung device Android Auto is an `UPDATED_SYSTEM_APP` whose factory version is a **non-functional stub**. `pm uninstall-system-updates` leaves the phone with no working Android Auto at all and needs a Play Store reinstall, which only gives you the newest version back. This was learned the hard way.

---

## 14. Projection dies when a Bluetooth keyboard connects

**Symptom** - projection drops the moment a Bluetooth keyboard connects or disconnects. Typing is fine; the transitions are what kill it.

**Cause** - `AapProjectionActivity` does not declare `keyboard` or `keyboardHidden` in `android:configChanges`, so Android destroys and recreates the activity on a keyboard configuration change. Screen geometry is negotiated once at handshake and cannot survive that.

Filed upstream as [#912](https://github.com/andreknieriem/open-headunit/issues/912) with a one-line fix.

**It bites harder than it reads.** A BLE keyboard that sleeps when idle reconnects on its own, so this fires repeatedly with nobody touching anything. If you also run software that *heals* keyboard reconnections, the two features fight each other.

**Workaround** - turn the keyboard off while driving. You should not be typing at the wheel regardless.

---

## 15. Projection starts when you are not driving

**Symptom** - Android Auto appears on the head unit while you are using the tablet for something else, typically after tethering it to the phone.

**Cause** - three conditions have to be true for projection, and all three quietly stay true outside the car:

| Condition | True outside the car? |
|---|---|
| Head unit server listening on 5277 | yes, it persists until phone reboot |
| Receiver app running and probing | yes, if close-on-disconnect is off |
| Both devices on the same network | yes, whenever you tether or share Wi-Fi |

Nothing in that set is car-specific, so the head unit connects whenever it can.

> **This is the disambiguation problem from [Architecture](01-architecture.md#the-context-disambiguation-problem) returning through a different door.** The original rule still holds: the hotspot is not a car signal, because you tether for work too. The head unit server route reintroduced the problem because the **head unit** now decides when to connect, and it has no idea whether you are driving.

**Gating options, best first:**

1. **Native mode.** It inverts control: the phone decides when to project, keyed to the car's Bluetooth. Structurally car-only, and it needs no developer-mode server. Requires a Bluetooth restart and re-pair, and tablets that cannot read their own Wi-Fi MAC need a Static BSSID entered by hand.
2. **Gate the receiver.** Auto-start on Bluetooth pointed at a device that exists only in your car, with close-on-disconnect on, so it is not armed anywhere else.
3. **Gate the server.** Start it getting in, stop it getting out. Works today, but two actions per drive and easy to forget.

**Do not gate on the hotspot or on the head unit joining a network.** Those fire during ordinary tethering, which is the whole problem.


---

# Dead ends

Approaches that seemed sound and are not. Documented so nobody re-derives them.

## Dead end A - turn the phone's Wi-Fi client off while driving

**The idea** - with no station link there is no channel to collide with, so the corruption disappears.

**Why it seemed right, and partly is:** `wlan0` (client) and `swlan0` (hotspot) are genuinely separate interfaces. `svc wifi disable` really does leave the SoftAP up with the tablet still connected. The phone uses mobile data for Maps and music. Technically sound.

**Why it fails:** the helper **hard-blocks it.** Pressing START with the Wi-Fi client disabled produces:

> **"Wi-Fi is Disabled! Open Headunit needs Wi-Fi to connect."** - Cancel / TURN ON WI-FI

The app calls `isWifiEnabled()`, which reports false when the client is off regardless of the SoftAP serving happily. There is no way around it from the outside.

**Use instead:** the 2.4 GHz band separation in [§2](#2-video-corrupts-into-macroblocks). Same result, no fight with the app.

## Dead end B - phone-to-tablet Bluetooth as a trigger

**The idea** - have the phone connect to the tablet over Bluetooth when the car audio device connects, so the tablet's `ACL_CONNECTED` receiver cold-starts the app. Zero drain, zero taps.

**Why it fails:** **two Android devices share no auto-connecting Bluetooth profile.** Pairing succeeds, but the ACL link never comes up on its own - tested, stayed `N` for 36 seconds, app never started.

A headset auto-connects because it is an audio **sink** the phone connects *to* and holds. A phone and a tablet are both sources and gateways. The profiles on offer are `ObexObjectPush AudioSource Avrcp HSP_AG PANU NAP Handsfree_AG` - the only linkable one is **PAN tethering**, which vendors gate behind *"To use Bluetooth tethering, turn off Wi-Fi on this phone."* Wi-Fi is what the projection needs. The path self-defeats.

**Silver lining:** because they never auto-connect, the pairing is harmless to leave in place and **can never false-trigger at home.**

## Dead end C - showing tablet battery in the Android Auto UI

**The idea** - surface the tablet's charge level on the projected screen.

**Why it fails:** the receiver declares exactly two sensors - `SENSOR_TYPE_DRIVING_STATUS` and `SENSOR_TYPE_NIGHT`. There is no battery sensor, no setting, and no string for one anywhere in the app.

More fundamentally, **the battery icon in the Android Auto UI is the phone's.** Android Auto draws it. A head unit has no channel to inject its own. This is a protocol limitation, not a missing feature.

**Only options:** swipe down on the tablet, or file an upstream feature request.

## Dead end D - SSID-based auto-launch

**The idea** - when the tablet joins the phone's hotspot, launch the head unit app.

**Why it fails:** hotspot connection is **not a car signal.** If you ever tether that tablet for ordinary work, the app launches over whatever you are doing. The one truly car-exclusive signal is Bluetooth to the car audio device. See [the disambiguation problem](01-architecture.md#the-context-disambiguation-problem).

**Also:** the app's own Wi-Fi auto-start is hidden on Android 13+ - the source gates it behind `SDK_INT <= 32`.

## Dead end E - routing audio out of the tablet

**The idea** - use the tablet as the audio source too, wired into the stereo.

**Why it fails, three ways:**
1. Charging the tablet from the car's 12 V while it is wired to the stereo **re-creates the exact ground loop** you were trying to escape. Clean only on battery.
2. Many modern tablets have **no analogue audio out over USB-C** - Samsung dropped accessory mode. They report `usb_headset`, so it looks possible, but it needs an *active* USB-C DAC.
3. That USB-C port is also the charging port, so you additionally need a power-delivery passthrough hub.

All of that to reach a **worse** outcome than the free alternative, which is leaving the audio on the phone's own Bluetooth path.

## Dead end F - scripted broadcast as the car workflow

**The idea** - the `am broadcast` trigger works beautifully on the bench; automate it for the car.

**Why it fails:** the native flow passes a **`PARAM_SERVICE_WIFI_NETWORK` Network parcelable**, which `am broadcast` cannot construct. The shell path only works **same-subnet**, and fails across a hotspot boundary. It also needs a PC running adb on the network, which you will not have in the car.

It is a fine bench tool. It is not a car solution. [Details](05-protocol-notes.md#the-broadcast-fallback).

---

## Still stuck?

Capture a drive with [the diagnostic tooling](06-diagnostics.md), then open an issue with:

- Phone model, tablet model, Android versions
- Android Auto version, receiver app version
- What you see versus what you expect
- Scrubbed `wifi.csv` - **remove SSIDs and MAC addresses first**
- Both frequency readings:
  ```bash
  adb -s <PHONE>  shell dumpsys wifi | grep -i frequency
  adb -s <TABLET> shell dumpsys wifi | grep -i frequency
  ```

That last pair answers the single most common cause before anyone has to ask.

---

**Next:** [08 - Roadmap](08-roadmap.md)
