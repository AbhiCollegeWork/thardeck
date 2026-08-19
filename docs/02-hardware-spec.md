# 02 - Hardware Specification

Every component in the reference build, with the numbers that were actually measured rather than the ones on the box.

> **Substituting parts?** The [requirements table](#minimum-requirements) at the bottom lists what genuinely matters. Most of the specific models here are interchangeable.

---

## Reference build

### Source device (the phone)

| Property | Value | Notes |
|---|---|---|
| Device | Samsung Galaxy S25 Ultra (SM-S938B) | Any Android Auto capable phone works |
| Android Auto version | 17.2 at time of testing | **Matters** - see [protocol notes](05-protocol-notes.md#version-sensitivity) |
| Wi-Fi | Wi-Fi 7, single radio | Single radio is the constraint that drives the [channel problem](01-architecture.md#network-topology) |
| Role | Runs navigation, media, calls; hosts hotspot; sources Bluetooth audio | Does all the actual work |

### Display device (the tablet)

| Property | Value | Notes |
|---|---|---|
| Device | Samsung Galaxy Tab S9 FE+ (SM-X610) | **Wi-Fi-only SKU** - no cellular |
| Panel | 12.4", 2560×1600, 16:10 | Android Auto streams 16:9, so there is letterboxing |
| Video decoder | `c2.exynos.hevc.decoder`, max 8192×8192 | H.265 capable; not a bottleneck |
| Projection resolution | 1080p | Verified - *not* 1440p. Do not chase bitrate theories |
| Projection DPI | **218** ("L" preset) | Default 172 was too small to hit while driving |
| Measured frame rate | 50-59 FPS, 5-17 ms frame time | Healthy |
| Role | Display and touchscreen only | **Zero audio output** |

**The Wi-Fi-only SKU matters:** without a cellular modem the tablet cannot host a hotspot, so the phone must be the access point. The 5G variant (SM-X616B) could in principle invert this topology, which would sidestep the STA+AP channel collision entirely. Untested here - an open question worth someone's time.

### Software

| Component | Version | Source |
|---|---|---|
| Open Headunit (tablet receiver) | 3.2.1 | [github.com/andreknieriem/open-headunit](https://github.com/andreknieriem/open-headunit) |
| AA Wireless Helper (phone) | 1.9.3 | Play Store |
| Android Auto (phone) | 17.2 | Play Store |

**Naming trap:** the app was formerly *Headunit Revived*. The package ID is still `com.andrerinas.headunitrevived`, but the classes moved to `com.andrerinas.openheadunit.*`. Launching the old activity path fails silently:

```bash
# Works
adb shell am start -n com.andrerinas.headunitrevived/com.andrerinas.openheadunit.main.MainActivity

# Fails - stale path from pre-3.2.0
adb shell am start -n com.andrerinas.headunitrevived/.main.MainActivity
```

The logcat tag also changed from `HUREV` to `OPENHU`. Grep for both.

---

## Audio chain

| Stage | Component | Spec |
|---|---|---|
| 1. Source | Phone A2DP | SBC/AAC/aptX depending on dongle |
| 2. Receiver | Bluetooth → 3.5 mm AUX dongle | Powered by a 12→5 V converter, **male** 3.5 mm output |
| 3. Isolation | 3.5 mm ground-loop isolator | Transformer coupled, female socket + male plug |
| 4. Head unit | Car stereo, AUX input | Any stereo with a 3.5 mm input |
| 5. Amplification | Active amplified subwoofer enclosure | Amp and sub in one box; trim-pot adjustments only |
| 6. Output | Factory door and dash speakers | - |

### Amplifier adjustments

Compact all-in-one amplified subwoofer units typically expose **recessed screwdriver trim-pots** rather than knobs, and they are easy to miss. On the reference unit there are two, beside the phase switch:

| Control | Range | Set to |
|---|---|---|
| Sensitivity (input gain) | 0.3 - 5.0 V | Toward the **0.3 V** end - the isolator drops signal level, so the amp needs to be more sensitive to compensate |
| Low-pass filter | 50 - 120 Hz | **120 Hz** (fully clockwise) - widens the band the sub covers, recovering upper-bass the isolator thinned |

There is no bass-boost control and no remote level knob on this class of unit. Check your own manual before assuming a control exists - but also before assuming it does not, since these trimmers are genuinely hard to see.

---

## The vehicle

A 2016 Mahindra Thar. Relevant because of what it does **not** have: no factory screen, no navigation, no Bluetooth, no Android Auto, no steering-wheel controls wired to anything useful. The system therefore assumes nothing from the car beyond two things:

| Requirement | Why |
|---|---|
| A 3.5 mm AUX input on the stereo | The only audio path in |
| A 12 V accessory socket | Powers the tablet and the Bluetooth dongle |

Any vehicle meeting those two runs this unchanged. A car that shipped with nothing is a useful proof of that.

---

## Electrical environment

The 12 V rail in this vehicle carries, besides the audio:

| Load | Noise risk |
|---|---|
| Camera displays | Low |
| 7× auxiliary camping lights | Low (not PWM dimmed) |
| **Bluetooth-controlled interior LED strips** | **HIGH - PWM dimmed** |
| 12→5 V converter feeding the audio dongle | Passes noise through: **common ground, no isolation** |

The LED controller was identified as the noise source by direct experiment: disconnecting it silenced the beep completely, and the pitch of the beep tracked LED colour and brightness. That is a PWM signature - the switching frequency changes with duty cycle, and it lands in the audio band.

**Why the existing 12→5 V converter did not help:** it is a *non-isolated* buck converter. It changes voltage but its input and output share a ground. Galvanic isolation is a different property from voltage conversion, and only the former breaks a ground loop. This is a common and expensive misunderstanding.

Full analysis: [04 - Audio Chain](04-audio-chain.md).

---

## Measured baselines

Reference numbers from a healthy system. If yours differ substantially, [06 - Diagnostics](06-diagnostics.md) explains how to read them.

### Network - healthy

```
RSSI:           -25 to -45 dBm
Link speed:     866 Mbps
Rx Link speed:  866 Mbps
STA frequency:  2462 MHz  (2.4 GHz)
AP frequency:   5745 MHz  (5 GHz)     <- different band, this is the point
Session:        ESTABLISHED on :5288
Time to connect: ~4 seconds
```

### Network - the failure mode

```
RSSI:           -25 to -32 dBm    <- still excellent!
Link speed:     104-144 Mbps
Rx Link speed:  1-6 Mbps          <- collapsed
STA frequency:  5745 MHz
AP frequency:   5745 MHz          <- IDENTICAL. This is the bug.
```

Plus, in logcat:
```
determineBeaconLossDisconnection: DISCONN bssid=<phone's own SoftAP> rssi=-94/-88
```
The phone losing beacons from *its own hotspot* is the smoking gun for radio contention.

### Rendering - healthy

| Metric | Value |
|---|---|
| Frame rate | 50-59 FPS |
| Frame time | 5-17 ms |
| Resolution | 1080p, rendered 2560×1600 landscape |
| Decoder errors | 0 |

### Power - parked

| Metric | Value |
|---|---|
| Wakelocks held | 0 |
| Wakefulness | `Dozing` |
| Foreground services | 0 |
| Process oom_score_adj | 900+ (cached) |

**Important correction to a widespread assumption.** An app sitting in the background with zero wakelocks is *not* necessarily free. Measured on an armed-but-backgrounded receiver:

```
oom_score_adj:   0          <- treated as foreground-important
wakelocks:       0          <- looks innocent
wakeupap events: 1177       <- it is NOT innocent
```

Those are device wakeups and Wi-Fi radio spin-ups attributed to the app despite it holding no wakelock. Checking only the wakelock list gives a false all-clear. Check `dumpsys batterystats | grep wakeupap=<uid>` and `oom_score_adj` too.

**Consequence:** the correct end-of-drive action is to **press the tablet power button**. Screen off means zero wakelocks and dozing, while the receiver stays armed for the next drive - zero drain *and* zero taps.

---

## Minimum requirements

For anyone substituting hardware:

| Requirement | Why | Flexible? |
|---|---|---|
| Phone with Android Auto | It is the whole computer | No |
| Phone can host a Wi-Fi hotspot | Carries the video link | No |
| Tablet on Android 8+ | Receiver app requirement | No |
| Tablet with hardware H.264 decode | Software decode will not sustain the frame rate | No |
| Tablet screen 8"+ | Smaller is unusable while driving | Somewhat |
| A Bluetooth audio path into the stereo | Keeps audio off the tablet | Yes - a wired AUX from the phone works, but re-creates the ground loop if the phone is also charging |
| Ground-loop isolator | Only if you have 12 V noise | Yes - skip it if your audio is already clean, since it costs you bass |

**Explicitly not required:** root, custom ROM, a specific vendor, a paid app, any dashboard disassembly, or any modification to the vehicle wiring.

---

**Next:** [03 - Setup Guide](03-setup-guide.md)
