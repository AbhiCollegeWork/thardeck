#!/usr/bin/env python3
"""
analyze-power - turn the power logs into a power matrix for both devices.

Neither device exposes an instantaneous current sensor to the shell, so the
logger records the fuel gauge coulomb counter instead. Differencing it gives
the exact charge moved in mAh, which is the integrated truth rather than a
noisy instant. Multiplied by voltage that becomes energy, and energy over
time is the average power the system actually draws.

    python analyze-power.py [directory containing power-tab.csv, power-phone.csv]

Caveat the output repeats, because it matters: while a device is charging the
counter shows the NET of charge in minus system draw. A clean figure for what
the system costs needs at least one drive with that device unplugged.
"""
import sys, os, glob
from datetime import datetime

DIR = sys.argv[1] if len(sys.argv) > 1 else "."

STATUS = {1: "unknown", 2: "charging", 3: "discharging", 4: "not charging", 5: "full"}


def load(path):
    rows = []
    for line in open(path, encoding="utf-8", errors="ignore"):
        p = line.strip().split("|")
        if len(p) < 9 or p[0].startswith("timestamp"):
            continue
        try:
            rows.append(dict(
                ts=datetime.strptime(p[0], "%Y-%m-%d_%H:%M:%S"),
                level=int(p[1]), chg=int(p[2]), mv=int(p[3]),
                temp=int(p[4]) / 10.0, status=int(p[5]),
                plugged=p[6] == "1", proj=p[7] not in ("0", ""), screen=p[8]))
        except (ValueError, IndexError):
            continue
    return rows


def segments(rows):
    """Contiguous runs sharing the same projecting and plugged state."""
    out, cur = [], None
    for r in rows:
        key = (r["proj"], r["plugged"])
        if cur and cur["key"] == key and (r["ts"] - cur["rows"][-1]["ts"]).total_seconds() <= 120:
            cur["rows"].append(r)
        else:
            if cur and len(cur["rows"]) > 2:
                out.append(cur)
            cur = dict(key=key, rows=[r])
    if cur and len(cur["rows"]) > 2:
        out.append(cur)
    return out


def rate(seg):
    """mA and mW. Sign convention: positive means the battery is losing charge."""
    a, b = seg["rows"][0], seg["rows"][-1]
    dt_h = (b["ts"] - a["ts"]).total_seconds() / 3600.0
    if dt_h <= 0:
        return None
    d_mah = (a["chg"] - b["chg"]) / 1000.0          # uAh -> mAh, drained is positive
    ma = d_mah / dt_h
    mv = sum(r["mv"] for r in seg["rows"]) / len(seg["rows"])
    return dict(hours=dt_h, mah=d_mah, ma=ma, mw=ma * mv / 1000.0,
                temp0=a["temp"], tempmax=max(r["temp"] for r in seg["rows"]),
                lvl0=a["level"], lvl1=b["level"])


def report(name, rows):
    print("=" * 74)
    print(f"{name}   {len(rows)} samples   {rows[0]['ts']} -> {rows[-1]['ts']}")
    print("=" * 74)

    segs = segments(rows)
    drive = [s for s in segs if s["key"][0]]
    idle = [s for s in segs if not s["key"][0]]

    print(f"\n{'state':<26}{'hours':>7}{'mAh':>9}{'mA':>9}{'mW':>9}{'temp':>13}")
    for label, group in (("PROJECTING", drive), ("idle", idle)):
        for s in group:
            r = rate(s)
            if not r or r["hours"] < 0.02:
                continue
            plug = "on charge" if s["key"][1] else "on battery"
            print(f"{label + ', ' + plug:<26}{r['hours']:>7.2f}{r['mah']:>9.0f}"
                  f"{r['ma']:>9.0f}{r['mw']:>9.0f}"
                  f"{r['temp0']:>6.1f}->{r['tempmax']:<5.1f}")

    # headline numbers, battery-only so they mean something
    def avg(group, plugged):
        rs = [rate(s) for s in group if s["key"][1] == plugged]
        rs = [r for r in rs if r and r["hours"] >= 0.05]
        if not rs:
            return None
        h = sum(r["hours"] for r in rs)
        mah = sum(r["mah"] for r in rs)
        mw = sum(r["mw"] * r["hours"] for r in rs) / h
        return dict(hours=h, ma=mah / h, mw=mw)

    d, i = avg(drive, False), avg(idle, False)
    print("\n  ON BATTERY, the figures that actually mean something")
    if d:
        print(f"    projecting : {d['ma']:>6.0f} mA   {d['mw']:>6.0f} mW   over {d['hours']:.2f} h")
    else:
        print("    projecting : no unplugged projecting time recorded yet")
    if i:
        print(f"    idle       : {i['ma']:>6.0f} mA   {i['mw']:>6.0f} mW   over {i['hours']:.2f} h")
    if d and i:
        print(f"    cost of running the system: {d['ma'] - i['ma']:.0f} mA "
              f"({d['mw'] - i['mw']:.0f} mW) above idle")

    cap = {"tab": 10090, "phone": 5000}.get(name.split()[0].lower())
    if d and cap:
        print(f"    at that rate a full {cap} mAh battery lasts about {cap / d['ma']:.1f} h")

    onchg = [s for s in drive if s["key"][1]]
    if onchg:
        tot = sum(rate(s)["hours"] for s in onchg if rate(s))
        print(f"\n  NOTE: {tot:.2f} h of projecting happened while charging. Those rows show")
        print("        NET charge, not what the system costs. For a true figure, do one")
        print("        drive with this device unplugged.")
    print()


found = False
for role in ("tab", "phone"):
    path = os.path.join(DIR, f"power-{role}.csv")
    if not os.path.exists(path):
        continue
    rows = load(path)
    if len(rows) < 4:
        print(f"{role}: only {len(rows)} samples so far, need a few more minutes\n")
        continue
    report(role.upper(), rows)
    found = True

if not found:
    print("No power logs found. Pull them first:")
    print("  adb pull /sdcard/Download/aa-diag/power-tab.csv")
    print("  adb pull /sdcard/Download/aa-diag/power-phone.csv")
