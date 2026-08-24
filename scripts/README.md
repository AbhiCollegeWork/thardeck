# Scripts

PowerShell and shell tooling for setting up and diagnosing a Thar Deck install.

> **Set your device serials first.** Each PowerShell script has `YOUR_TABLET_SERIAL` / `YOUR_PHONE_SERIAL` at the top. Find yours with:
> ```bash
> adb devices -l
> ```

**Requirements:** `adb` on PATH, PowerShell 5.1+, USB debugging or a paired wireless-debugging connection.

---

## `Start-DriveLogging.ps1`

Arms two detached captures on the tablet before a drive.

```bash
powershell -ExecutionPolicy Bypass -File Start-DriveLogging.ps1
```

Starts a rotating logcat plus a Wi-Fi link sampler (10 s interval), both via `nohup setsid` so they survive adb disconnecting, the screen going off, and the tablet switching networks.

**Does not survive a tablet reboot** - re-run afterwards.

Verify it is alive by watching the row count grow, **not** with `ps` (the sampler shows up as plain `sh`):
```bash
adb shell "wc -l < /sdcard/Download/aa-diag/wifi.csv"
```

---

## `Pull-DriveLogs.ps1`

Retrieves the captures, prints a summary, and clears the device side.

```bash
powershell -ExecutionPolicy Bypass -File Pull-DriveLogs.ps1
powershell -ExecutionPolicy Bypass -File Pull-DriveLogs.ps1 -KeepOnDevice   # skip cleanup
powershell -ExecutionPolicy Bypass -File Pull-DriveLogs.ps1 -StopLogging    # also stop capturing
```

Local copies land in timestamped folders, so repeated pulls never overwrite each other.

**Device files are deleted only after the local copy is verified** - file count and size are compared first, and a partial pull refuses to clean up. Reading the summary: [06 - Diagnostics](../docs/06-diagnostics.md#reading-the-results).

---

## `Stop-DriveLogging.ps1`

Kills both captures and removes every file, including the on-device helper script.

```bash
powershell -ExecutionPolicy Bypass -File Stop-DriveLogging.ps1
```

Warns first if un-pulled logs would be lost. **Pull before you stop.**

---

## `Start-AndroidAuto.ps1`

Forces a projection session from a PC. **Bench and diagnostic tool only** - it needs a PC on the same network, so it is not a car workflow.

```bash
powershell -ExecutionPolicy Bypass -File Start-AndroidAuto.ps1
```

Discovers both devices over mDNS, reads the tablet's current IP, cold-restarts Android Auto for a deterministic state, arms the receiver, fires the wireless-startup broadcast, then polls `/proc/net/tcp*` for an established session on port 5288 - retrying the broadcast once, because the first one after a force-stop is usually swallowed.

Useful when you want to test the projection path in isolation from discovery. Why it cannot be the car solution: [Protocol Notes](../docs/05-protocol-notes.md#why-this-cannot-be-the-car-solution).

---

## `aa-wifi-sampler.sh`

The on-device sampler, pushed to `/data/local/tmp/` by `Start-DriveLogging.ps1`. Appends one row per 10 seconds to `/sdcard/Download/aa-diag/wifi.csv`:

```
timestamp|ssid|rssi|link_speed|rx_speed|frequency|session=N
```

Session detection reads socket state directly (`14A8` = port 5288, state `01` = ESTABLISHED), so it is **UID-independent** and survives app reinstalls and version changes.

If pushing it by hand from Git Bash, prefix with `MSYS_NO_PATHCONV=1` or the path gets mangled into `C:/Program Files/Git/data/...`:
```bash
MSYS_NO_PATHCONV=1 adb push aa-wifi-sampler.sh /data/local/tmp/
```

---

## `tab-watch.sh` and `phone-watch.sh`

Supervised capture, deployed to `/data/local/tmp/` and started detached:

```bash
adb -s <DEVICE> push tab-watch.sh /data/local/tmp/
adb -s <DEVICE> shell "chmod 755 /data/local/tmp/tab-watch.sh"
adb -s <DEVICE> shell "nohup setsid sh /data/local/tmp/tab-watch.sh >/dev/null 2>&1 &"
```

`tab-watch.sh` runs on the tablet: filtered logcat, a watchdog that restarts it within 30 seconds of a silent death, and a line in `events.log` for every projection session transition with the Wi-Fi state at that instant.

`phone-watch.sh` runs on the phone and records the source side every five seconds: whether Android Auto is projecting, whether its process is alive, Bluetooth links, hotspot clients, screen state and battery temperature.

Use both together. A session has two ends, and the tablet alone can prove the network was fine without ever saying what ended the session. See [Troubleshooting §11](../docs/07-troubleshooting.md#11-screen-freezes-mid-drive-then-recovers).

**From Git Bash, prefix pushes with `MSYS_NO_PATHCONV=1`** or the destination path is rewritten.

---

## Privacy

Pulled logs contain **SSIDs, BSSIDs, MAC addresses and network history**. The repo `.gitignore` excludes `drive-logs/`, `*.csv` and `logcat*.txt` - keep it that way, and scrub before attaching anything to an issue. See [Diagnostics § Privacy](../docs/06-diagnostics.md#privacy).
