# Contributing

This project is one working installation documented thoroughly. The most useful thing you can add is **a second data point**.

---

## Most wanted

| Contribution | Why it matters |
|---|---|
| **Non-Samsung phone results** | The STA+AP channel-forcing behaviour that causes the worst bug may differ by vendor. Nobody has checked. |
| **A cellular tablet hosting the hotspot** | Would invert the topology and sidestep the channel collision entirely. The most interesting untested idea in the project. |
| **Record-while-projecting feasibility** | Decides whether the [dash cam plan](docs/08-roadmap.md) is viable at all. |
| **Other head units and audio chains** | Different AUX behaviour, different noise profiles, different isolator outcomes. |
| **Corrections** | If something here is wrong, say so. Everything was measured on one setup and generalisation is a guess. |

---

## Reporting hardware results

Open an issue with:

- Phone model + Android version + Android Auto version
- Tablet model + Android version + receiver app version
- What worked, what did not
- Which settings you had to change from this guide
- Scrubbed diagnostics if you have them

**Always include both frequency readings** — this answers the most common cause before anyone has to ask:
```bash
adb -s <PHONE>  shell dumpsys wifi | grep -i frequency
adb -s <TABLET> shell dumpsys wifi | grep -i frequency
```

---

## Scrub your logs

Drive logs contain **SSIDs, BSSIDs, MAC addresses and enough network history to infer where you have been.** Never paste them raw.

```bash
sed -E 's/SSID: "[^"]*"/SSID: "REDACTED"/g; s/([0-9a-f]{2}:){5}[0-9a-f]{2}/XX:XX:XX:XX:XX:XX/gi' wifi.csv > wifi-safe.csv
```

Check before posting: no SSIDs, no MAC or BSSID values, no home IP ranges, no device serial numbers, no location data.

---

## Documentation standards

The value of this repo is that it records *why*, not just *what*. When you add to it:

- **Include the evidence.** A measured number beats a claim. `RSSI -25 dBm with Rx at 1-6 Mbps` is worth more than "the signal was fine".
- **Document dead ends.** Approaches that failed save the next person more time than approaches that worked. Say why it failed and what to do instead.
- **Flag what is unverified.** If something was not road-tested, say so. Overstated confidence is worse than an admitted gap.
- **Distinguish measurement from inference.** "Measured X" and "which suggests Y" are different claims and should read differently.
- **Note version and hardware dependence.** Much of this is specific to particular versions and will drift.

---

## Code

- PowerShell scripts target 5.1 (Windows default) — no PowerShell 7 syntax
- Shell scripts target Android's `sh`, not bash — no bashisms
- Device serials stay as `YOUR_TABLET_SERIAL` / `YOUR_PHONE_SERIAL` placeholders
- Anything that deletes must verify first — see the pattern in `Pull-DriveLogs.ps1`
- No hardcoded IPs, SSIDs or MAC addresses

---

## Safety

This system runs in a moving vehicle. Contributions that affect in-car behaviour should say what happens when they fail, and should not make the driver look at the screen for longer or more often.

**The system's best property is that audio never depends on video** — a total failure of the screen still leaves navigation audible and the phone call connected. Do not propose changes that couple them.

---

## Scope

Out of scope, deliberately: rooting, vehicle wiring modifications, replacing the factory head unit, custom Android Auto clients, cloud upload, and anything requiring a subscription. See [the roadmap](docs/08-roadmap.md#explicitly-out-of-scope).

## License

Contributions are accepted under the [MIT License](LICENSE).
