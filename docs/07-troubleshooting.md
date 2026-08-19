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
