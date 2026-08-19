# 03 - Setup Guide

**The build manual.** Follow it in order - later steps assume earlier ones.

**Time:** about 90 minutes, plus one drive to validate.
**Difficulty:** no root, no soldering, no dashboard removal. Everything is a settings menu.

> **Do the whole setup parked.** Several steps involve watching the tablet closely. None of them should happen while the car is moving.

---

## Checklist

- [ ] [Phase 1 - Install software](#phase-1--install-software) (10 min)
- [ ] [Phase 2 - Configure the tablet](#phase-2--configure-the-tablet) (25 min)
- [ ] [Phase 3 - Configure the phone](#phase-3--configure-the-phone) (15 min)
- [ ] [Phase 4 - Bench test](#phase-4--bench-test) (15 min)
- [ ] [Phase 5 - Audio chain](#phase-5--audio-chain) (20 min)
- [ ] [Phase 6 - Physical install](#phase-6--physical-install) (15 min)
- [ ] [Phase 7 - First drive](#phase-7--first-drive) (one drive)

---

## Phase 1 - Install software

### On the tablet
1. Install **Open Headunit** from [its releases page](https://github.com/andreknieriem/open-headunit/releases). Use **3.2.1 or newer** - earlier versions have Wi-Fi Direct and reconnect bugs that will waste your evening.
2. Grant every permission it asks for: Location, Nearby devices, Bluetooth.
3. Also grant **Display over other apps** - it is easy to skip and the release notes state it improves compatibility.
4. Exclude it from battery optimisation: Settings → Apps → Open Headunit → Battery → **Unrestricted**.

> **Do not install Android Auto on the tablet.** It is not needed and it is not how this works. If you have installed it chasing the app's "Self Mode", uninstall it - see [Troubleshooting](07-troubleshooting.md#9-self-mode-hangs-at-the-animation).

### On the phone
1. Install **AA Wireless Helper** from the Play Store.
2. Confirm **Android Auto** is installed and has run at least once.
3. Grant the Helper Location and Nearby devices permissions.

---

## Phase 2 - Configure the tablet

This is the phase where care pays off. Nearly every "it does not work" report traces back to one of these settings.

> ### ⚠️ Read this before touching any setting
> **Each settings sub-screen has its own Save button, and navigating away discards your change.** A setting can silently revert with no warning at all - during this build, "Start on boot" reverted twice before it stuck.
>
> **The reliable procedure for every setting below:**
> 1. Change it
> 2. Tap **Save on that same screen**
> 3. Back out, re-enter, confirm it held
> 4. For anything critical, force-stop the app and check again - preferences are only re-read from disk on a cold start
>
> **Second trap:** the settings search box does **not** clear between searches. Typing a new term appends to the old one, producing nonsense like `auto-startdisconnect` and an empty result list. **Tap the X first, every time.**

### 2.1 - Connection strategy (the one that makes it work at all)

**Settings → ADVANCED → Wireless Connection → Helper Connection Strategy → `Phone Hotspot (Host)`**

The default is *Google Nearby (Beta)*. If the phone Helper is set to Shared Wi-Fi / Hotspot while the tablet is on Nearby, the two use **different discovery protocols** and deadlock permanently - both ends listening, neither advertising. There is no error message. It simply searches forever.

If you fix nothing else, fix this.

### 2.2 - Audio Sink OFF (keeps sound in the car)

**Settings → ADVANCED → search `audio` → Audio Sink → OFF → Save**

The in-app description: *"If disabled, the headunit will not receive audio. Sound will play from the phone."*

With this off, the tablet stops advertising the media (`AUD`) and speech (`AU1`) audio sinks during handshake, but keeps the system sink (`AU2`) - the protocol requires at least one to hold the connection open. So you keep display, touch and media controls, and lose only audio output. Verified live: the phone's Android Auto requests audio focus and immediately abandons it, and no Android Auto audio device appears in the phone's routing table.

Fully reversible if you ever want tablet audio.

### 2.3 - Orientation lock (two layers, both required)

**Layer 1 - the app:** Settings → BASIC → search `orient` → **Screen orientation = Landscape (0°)** → Save

**Layer 2 - the system:** on Android 12L and newer, large-screen devices *ignore* apps' orientation requests, so layer 1 alone does nothing.

```bash
adb shell cmd window set-ignore-orientation-request false
```

**Side effect you should know about:** this restores the ability of *all* apps on the tablet to lock orientation. Portrait-only apps may now force the tablet into portrait. Revert with `set-ignore-orientation-request true`.

**Alternative if you dislike that side effect** - lock the whole tablet to landscape instead:
```bash
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
```

**Why this matters so much:** Android Auto negotiates screen geometry exactly once, during handshake. The receiver deliberately calls `SCREEN_ORIENTATION_LOCKED` because live rotation is *architecturally impossible* in the protocol. Whatever orientation the session starts in is baked in until you reconnect. Determinism before connect is the only available fix.

**Persistence caveat:** the system-level override is written to `/data/system/display_settings.xml`, which is unreadable without root, so persistence across reboot is unconfirmed. If orientation regresses after a tablet restart, re-run the command.

### 2.4 - Pixel density (driving safety)

**Settings → BASIC → search `density` → Pixel density (DPI) → `L` (218) → Save**

The default 172 DPI gives about 1515 dp of width. Real car head units are 1000-1200 dp, so at the default everything renders roughly 30% smaller than the UI was designed for - the on-screen keyboard especially. At 218 DPI touch targets are ~27% larger.

The in-app help confirms the direction: *"A higher DPI makes Android Auto icons and text larger, so less fits on screen."* Go to XL if still too small for you.

Takes effect on the next connection.

### 2.5 - Auto-start: turn it all off

**Settings → Auto-start settings**

| Setting | Value | Why |
|---|---|---|
| Start on boot | **OFF** | |
| Start on screen on | **OFF** | Would fire every single time you use the tablet normally |
| Auto-start on Bluetooth | **Not set** | See the warning below |

> **⚠️ The bug that will annoy you most.** During this build, "Auto-start on Bluetooth" was left pointing at the *phone*. Result: the head unit app launched itself over ordinary work whenever phone and tablet made momentary Bluetooth contact. The logcat trail:
> ```
> OPENHU: AutoStartReceiver.onReceive | BT Device connected: <phone>
> OPENHU: MATCH! Starting AapService via Bluetooth Auto-start
> ```
> Only ever set this to a device that exists **exclusively in your car**. Never your phone, never a home speaker.
>
> To clear it, use the picker's **Remove** button - you cannot untick it.

### 2.6 - Turn off the FPS overlay

**Settings → BASIC → search `fps` → Debug → Show FPS Counter → OFF**

On by default in some builds. Useful for diagnosis, distracting while driving.

### 2.7 - Close app on disconnect

**Recommended: OFF.**

With it ON you must manually reopen the app every drive, and the measured benefit is zero - a backgrounded receiver already holds no wakelocks. The app itself warns that ON is unsafe in combination with "Start on boot", because a *sleeping* (not rebooted) tablet never fires `BOOT_COMPLETED`.

### 2.8 - Verify it all stuck

```bash
adb shell am force-stop com.andrerinas.headunitrevived
adb shell am start -n com.andrerinas.headunitrevived/com.andrerinas.openheadunit.main.MainActivity
```

Re-open settings and confirm every value above. **Cold restart is the only honest test** - preferences are re-read from disk only on a fresh process.

---

## Phase 3 - Configure the phone

### 3.1 - Hotspot band (prevents the video corruption)

**Settings → Mobile Hotspot → Configure → Band → `2.4 GHz` / "Compatibility"**

Not "5 GHz preferred", not "Performance". This is the single fix for [macroblock corruption while driving](01-architecture.md#the-failure-co-channel-collision).

> **The band preference is not applied while the phone holds a 5 GHz station link.** Right after saving, the hotspot may still report 5 GHz. **Cycle the hotspot off and on**, then verify:
> ```bash
> adb shell dumpsys wifi | grep -i "frequency"
> ```
> You want the STA and AP frequencies to be in **different bands**. Same number on both = you are still broken.

Also turn **"One-time password" OFF** so the tablet can rejoin without a prompt.

### 3.2 - Bluetooth audio dongle

1. Power the dongle from the car's 12 V (via its converter).
2. Pair the phone to it. **Name it something unmistakable** - you will reference it in automation.
3. Confirm music plays through the car speakers.

### 3.3 - Wireless Helper

| Setting | Value |
|---|---|
| Auto Start Service | **On Bluetooth Connection** |
| Bluetooth device | **your car audio dongle, and nothing else** |
| Try to auto reconnect | ON |
| Stop on BT disconnect | ON |

> **Check the device list has exactly one entry.** During this build a leftover home Bluetooth speaker was still ticked from an earlier test, which would have launched projection every time that speaker was used at home. Untick everything except the car device.

### 3.4 - Car-only audio profile (optional but recommended)

If your isolator thinned the bass, or you just want a different sound in the car, scope an EQ profile to the car - full walkthrough in [04 - Audio Chain](04-audio-chain.md#the-car-only-eq-profile).

Short version: build a routine with **If: Bluetooth device `<car dongle>` connected → Then: Equaliser = Custom**. The automation platform adds the revert automatically, so it returns to normal when you park. Your headphones stay untouched.

---

## Phase 4 - Bench test

Do this at home, parked, before you rely on it.

1. Turn the phone hotspot **on**.
2. Let the tablet join it.
3. Open **Open Headunit** on the tablet → tap the **Wi-Fi** tile.
4. Confirm the receiver is listening:
   ```bash
   adb shell "cat /proc/net/tcp /proc/net/tcp6" | grep 14A8
   ```
   `14A8` is hex for 5288. A row in state `0A` means listening.
5. Connect the phone to the Bluetooth dongle (or press START in the Helper).
6. Within a few seconds the tablet should flip to the projection UI.
7. Confirm a live session - state `01` is ESTABLISHED:
   ```bash
   adb shell "cat /proc/net/tcp /proc/net/tcp6" | awk '$2 ~ /14A8$/ && $4 == "01"'
   ```

### Acceptance criteria

| Check | Expected |
|---|---|
| Picture | Landscape, full width, no cropped edges |
| Touch | Responsive, no offset |
| **Audio** | **Car speakers - not the tablet.** Play something and confirm. |
| Keyboard | Comfortably hittable at arm's length |
| Maps | Renders and follows GPS |
| Frame rate | Smooth, no stutter |

If any of these fail, go to [07 - Troubleshooting](07-troubleshooting.md) before continuing.

---

## Phase 5 - Audio chain

1. **Listen for noise first**, before installing the isolator. Engine running, LED strips and accessories on, volume up, nothing playing. A whine, hiss or beep that changes with engine RPM or with your lighting is a ground loop.
2. **If it is clean, stop here.** An isolator costs you low end. Do not fit one you do not need.
3. **If there is noise:** fit the isolator between the dongle output and the stereo input. Verify the connector genders before ordering - a dongle with a *male* output needs an isolator with a *female* socket on that side.
4. **Re-test.** Noise should drop dramatically.
5. **Now check the bass.** Play something you know well. If the low end has gone thin, that is the transformer, and it is expected. Fix it in this order:
 - Amplifier **Sensitivity** trim toward the 0.3 V end
 - Amplifier **Low-pass** trim to 120 Hz
 - Phone EQ compensation curve - see [04 - Audio Chain](04-audio-chain.md)

---

## Phase 6 - Physical install

**Mounting rules, in priority order:**

1. **Never block your view of the road.** Below the dash line, not on the windscreen in front of you.
2. **Never in an airbag deployment path.** A tablet becomes a projectile.
3. Reachable without leaning - if you have to stretch, you are looking away too long.
4. Angled away from glare.
5. Cables routed so nothing fouls the gear lever, handbrake or pedals.

**Check your local law.** Screen placement rules vary by jurisdiction, and some are specific about what may be visible to the driver.

**Power:** the tablet needs a USB-C charger for anything beyond a short trip - a projection session is a sustained load. Prefer a socket that dies with the ignition so it cannot flatten the car battery overnight.

---

## Phase 7 - First drive

**Before you leave**, arm diagnostic logging so you get data from the first real drive rather than the third:

```bash
powershell -ExecutionPolicy Bypass -File scripts/Start-DriveLogging.ps1
```

Then drive normally and note anything odd - especially in the first few minutes near home, when the [channel collision](01-architecture.md#the-failure-co-channel-collision) would show up if it is still present.

**Afterwards:**

```bash
powershell -ExecutionPolicy Bypass -File scripts/Pull-DriveLogs.ps1
```

This pulls the captures, prints a summary, and clears the device side **only after verifying the local copy**. What the numbers mean: [06 - Diagnostics](06-diagnostics.md).

### The daily routine, once it works

**Getting in:** hotspot on (or automatic) → open the app → tap Wi-Fi. About five seconds.

**Getting out:** **press the tablet's power button.** Screen off = zero wakelocks = zero drain, while the receiver stays armed for next time.

---

## Optional - scripted startup

If you have a PC on the same network, [`scripts/Start-AndroidAuto.ps1`](../scripts/Start-AndroidAuto.ps1) automates the whole handshake for bench testing. It is a **development and diagnostic tool, not a car workflow** - it needs a PC on the network, which you will not have on the road.

See [05 - Protocol Notes](05-protocol-notes.md#the-broadcast-fallback) for how it works and why it cannot replace the native flow.

---

**Next:** [04 - Audio Chain](04-audio-chain.md) · [07 - Troubleshooting](07-troubleshooting.md)
