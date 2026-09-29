# Thar Deck Wave: hand-gesture media control

Design and build specification. This file is the contract between the planning
pass and the execution passes. Read all of it before writing code.

## Goal

Control music in the car with hand gestures in front of the dashboard tablet,
while driving, without looking at the screen:

| Gesture (as the driver sees their own hand) | Action |
|---|---|
| Index finger circling clockwise | volume up, one step per part-turn, like a rotary knob |
| Index finger circling anticlockwise | volume down, same feel |
| Open hand swipe to the driver's right | next track |
| Open hand swipe to the driver's left | previous track |
| Closed fist held still about half a second | play or pause |

The premium-car reference is BMW's gesture control. The feel to copy is the
rotary volume: continuous, proportional, no discrete "up" chirps.

## Physical facts that shape the design

- The tablet (Galaxy Tab S9 FE+, Android 16, SDK 36, no root) is mounted
  landscape on the dashboard running Open Headunit full screen as an Android
  Auto receiver. Its front camera faces the driver. Sensor orientation is 270
  degrees, hardware level LIMITED, and it offers a 320x240 stream.
- **Audio does not pass through the tablet.** Music plays from the phone over
  Bluetooth A2DP to the car audio. So "volume" is the phone's STREAM_MUSIC, and
  play, pause, next and previous are media key events on the phone. A gesture
  recognised on the tablet must therefore be relayed to the phone. There is
  already a companion app on the phone (`companion/`, package
  `com.abhi.thardeck`) running a foreground service; the relay lives there.
- Both devices sit on the phone's hotspot during a drive. On that network the
  phone is the tablet's default gateway, which gives a discovery path that
  needs no configuration. At a desk both may be on home Wi-Fi instead, so
  discovery must also work by subnet broadcast.
- The receiver app owns the whole screen. Feedback to the driver has to be a
  small overlay window drawn over it, and must never take touch or focus.
- The receiver decodes 1080p video at 30 fps. The gesture pipeline must not
  cost it frames. The receiver prints a line every five seconds,
  `Throughput over ... dropped=N, skipped=N`, and the acceptance rule is that
  these stay at their baseline (0) with the gesture pipeline running and a
  hand in view.
- Nothing personal goes in the repo: no MAC addresses, IP addresses, device
  serials, or network names. No em dashes or en dashes anywhere in any file.
- Third-party code is not our work and is credited: MediaPipe Hand Landmarker
  (Google, Apache 2.0) does the hand tracking; CameraX (AOSP) does the camera.

## Architecture

```
tablet: Wave app                                 phone: companion app
  CameraX front camera 320x240                     ServerService (existing FGS)
    -> MediaPipe HandLandmarker (LIVE_STREAM)        + MediaRelay: UDP listener :5299
      -> GestureEngine (state machine)                  VOL_UP/DOWN -> AudioManager STREAM_MUSIC
        -> CommandSender (UDP, discovery)  ---------->  PLAY_PAUSE/NEXT/PREV -> dispatchMediaKeyEvent
        -> Overlay HUD (over the receiver)             PING -> PONG (discovery reply)
```

Two deliverables, built in parallel by two execution passes:

- **Track A, phone:** extend `companion/` with the media relay. Directory:
  `thardeck/companion/`. No-Gradle build via its existing `build.ps1`.
- **Track B, tablet:** new app `thardeck/wave/`, package
  `com.abhi.thardeck.wave`, label "Thar Deck Wave". Gradle build.

## Protocol (shared by both tracks, do not deviate)

UDP, phone listens on port **5299**. One ASCII line per datagram, no newline
required:

```
TD1 <token> <CMD>
```

`CMD` is one of `PING`, `VOL_UP`, `VOL_DOWN`, `PLAY_PAUSE`, `NEXT`, `PREV`.
`token` is a shared secret, default `thardeck`, changeable on both sides. A
datagram with the wrong token is dropped silently.

Reply to `PING` only, sent back to the sender's address and port:

```
TD1 PONG <phone model name>
```

Phone actions:

- `VOL_UP` / `VOL_DOWN`: `AudioManager.adjustStreamVolume(STREAM_MUSIC,
  ADJUST_RAISE | ADJUST_LOWER, FLAG_SHOW_UI)`.
- `PLAY_PAUSE`, `NEXT`, `PREV`: `AudioManager.dispatchMediaKeyEvent` with a
  KeyEvent ACTION_DOWN then ACTION_UP for `KEYCODE_MEDIA_PLAY_PAUSE`,
  `KEYCODE_MEDIA_NEXT`, `KEYCODE_MEDIA_PREVIOUS`. This is public API and
  reaches whatever media session is active on the phone.

Tablet discovery, in this order, caching the first responder for ten minutes
and re-probing on any Wi-Fi change or after three unanswered commands:

1. Send `PING` to the default gateway (the phone, when on its hotspot).
2. Send `PING` to the subnet broadcast address.
3. Send `PING` to a manually configured address, if the user set one.

Commands are fire-and-forget; the tablet does not wait for acknowledgement.

## Track A: phone media relay (companion app)

Add to `thardeck/companion/`:

- `MediaRelay.java`: a thread owning a `DatagramSocket` on 5299, parsing the
  protocol above, applying actions through `AudioManager`, replying to PING.
  Owned by `ServerService` (start in `onCreate`, stop in `onDestroy`). Must
  survive the socket being torn down by a network change: on `IOException`,
  close, wait one second, reopen, loop.
- Token stored in the app's prefs (`Hu`), default `thardeck`. Add
  `ACTION_SET_TOKEN` to `ControlReceiver` (`--es token <value>`).
- Manifest: add back `android.permission.INTERNET` (sockets need it; it was
  removed earlier because the loopback check was dropped) and
  `android.permission.MODIFY_AUDIO_SETTINGS` is **not** required for
  adjustStreamVolume; do not add permissions that are not needed.
- Log every applied command at tag `THARDECK` as `relay: <CMD> from <sender>`
  and the PONG. Never log the token.
- Show the relay state on the existing status screen and notification text
  ("relay listening on 5299").
- Update `companion/README.md` with a short "Media relay" section and the
  SET_TOKEN command.
- Build with `powershell -ExecutionPolicy Bypass -File build.ps1` and make
  sure it passes. If, and only if, `adb devices` lists the phone, install
  with `adb -s <phone> install -r -g` and verify
  with `adb shell dumpsys audio | grep -A3 STREAM_MUSIC` before and after a
  VOL_UP sent from the PC with a Python one-liner over UDP to the phone's
  Wi-Fi address. If the phone is absent, stop after the build passes and say so.

## Track B: tablet gesture app (Wave)

### Project

`thardeck/wave/` as a standard Android Gradle project, Java 17, single module
`app`. Use the local Gradle launcher, there is no wrapper script:

```
C:\Users\Priya\.gradle\wrapper\dists\gradle-8.14.3-all\10utluxaxniiv4wxiphsi49nj\gradle-8.14.3\bin\gradle
```

AGP 8.13.2 is already in the Gradle cache; use exactly that version.
compileSdk 36, targetSdk 34, minSdk 31. Dependencies: `androidx.camera:camera-camera2`,
`camera-lifecycle`, `camera-view` (1.4.x), and
`com.google.mediapipe:tasks-vision:0.10.21` (or the newest 0.10.x that
resolves). These download from Maven; the machine has network.

The hand model is a 7.8 MB binary. Do not commit it. Add a Gradle task that
downloads it into `app/src/main/assets/hand_landmarker.task` before
`preBuild` if missing, from:

```
https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task
```

and gitignore `*.task`, `build/`, `.gradle/`, `*.apk`, `local.properties`.
Write `local.properties` with `sdk.dir` pointing at
`C:\Users\Priya\AppData\Local\Android\Sdk`.

Sign with the standard debug keystore. The build must produce
`app/build/outputs/apk/debug/app-debug.apk`.

### Components

- **`GestureService`**: foreground service, `foregroundServiceType="camera"`,
  permission `FOREGROUND_SERVICE_CAMERA`. Owns the camera and the pipeline.
  Notification with Pause and Resume actions. Started from `MainActivity`
  (camera foreground services cannot be started from the background on this
  Android version). On any camera or landmarker error: log, release, wait two
  seconds, retry, with backoff up to thirty seconds.
- **Camera**: CameraX `ImageAnalysis`, front lens, target resolution 320x240,
  `STRATEGY_KEEP_ONLY_LATEST`, backpressure dropping. Convert to an MPImage
  for MediaPipe. Account for the 270 degree sensor orientation and the fact
  that the front image is mirrored: **all engine coordinates are normalised to
  the driver's frame of reference, where +x is the driver's right.** Get this
  right once, in one place, and log which transform was chosen.
- **Landmarker**: MediaPipe `HandLandmarker`, `RunningMode.LIVE_STREAM`,
  `numHands=1`, delegate GPU if it initialises, else CPU. Result listener feeds
  the engine. Throttle: when no hand has been seen for two seconds, analyse
  at about 5 fps by skipping frames; when a hand is present, up to 15 fps.
  Never more. This is the main lever for not costing the receiver frames.
- **`GestureEngine`**: a pure-Java state machine with no Android imports, so
  it can be unit tested with synthetic landmark sequences. Input per frame:
  timestamp, hand present, 21 normalised landmarks in the driver frame, and
  the hand bounding box. Output: zero or more commands. Rules:
  - **Arming.** A hand only counts when its bounding box height is at least
    0.28 of the frame (close to the camera, so a hand on the wheel does not
    trigger) and it has been present for at least 150 ms.
  - **Rotate (volume).** Track the index fingertip (landmark 8) in a 700 ms
    ring buffer. Centre is the mean of the buffer. Require the mean radius to
    be at least 0.05 and the points to spread around the centre (not a line).
    Accumulate the signed angle between successive points about the centre.
    Every time the accumulator passes plus 50 degrees emit `VOL_UP` and
    subtract 50; minus 50 emits `VOL_DOWN`. Clockwise as the driver sees it is
    up. Cap at 8 steps per second. Rotation stays active while the hand keeps
    circling; it releases after 400 ms without rotation.
  - **Swipe (next, previous).** Hand centre is the mean of landmarks 0, 5, 9,
    13, 17. A horizontal displacement of at least 0.30 within 450 ms, with
    vertical travel under half the horizontal, and no rotation in progress,
    emits `NEXT` for the driver's right and `PREV` for the left. One event,
    then a 600 ms cooldown.
  - **Fist hold (play, pause).** A fist is when, for index, middle, ring and
    pinky, the fingertip is closer to the wrist (landmark 0) than that
    finger's PIP joint is. A fist held for 500 ms emits `PLAY_PAUSE` once and
    then requires the hand to open before it can fire again.
  - Only one recogniser acts at a time; rotation has priority while active.
  - All thresholds live in one `Tuning` class with the defaults above, so the
    calibration session can adjust them from the app screen without a rebuild.
- **`CommandSender`**: the discovery and UDP send described in the protocol
  section, on its own thread. Cached target, re-probe rules, and a
  `manualAddress` pref. Log sends and PONGs at tag `THARWAVE`.
- **Overlay HUD**: `TYPE_APPLICATION_OVERLAY` window, `FLAG_NOT_TOUCHABLE |
  FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN`, a small pill at the top centre
  of the screen. Shows an icon and a word for 900 ms on each discrete command
  ("Next", "Previous", "Pause", "Play"). While rotation is active it shows a
  ring with an arc that grows with the accumulator and a "+" or "-". Nothing
  else, no persistent chrome. Needs `SYSTEM_ALERT_WINDOW`, granted by adb
  appops at install.
- **`MainActivity`**: status (service state, camera fps, inference ms, hand
  present, last command, relay target found or not), Start and Stop, a
  "Calibrate" view that shows the camera preview with landmarks drawn and the
  engine's live state so a person can tune thresholds, the tuning sliders,
  token and manual address fields, and an "autostart on boot" switch.
- **`BootReceiver`**: if autostart is on, start `MainActivity` (allowed from
  the background because the app holds the overlay permission), which starts
  the service and finishes itself.
- **Simulation hook**: an exported receiver for
  `com.abhi.thardeck.wave.SIM --es cmd NEXT` that pushes a command straight
  into `CommandSender` and the HUD, so the network path and the phone can be
  tested without a hand in front of the camera.
- **Logging**: tag `THARWAVE`. Every engine state transition and emitted
  command, every discovery result, and a line every five seconds:
  `stats: analysed=N fps=F infer_ms=M hand=P%`.

### On-device verification for track B (the tablet is on USB)

1. `adb install -r -g app-debug.apk`; then
   `adb shell appops set com.abhi.thardeck.wave SYSTEM_ALERT_WINDOW allow`,
   `adb shell dumpsys deviceidle whitelist +com.abhi.thardeck.wave`,
   `adb shell cmd appops set com.abhi.thardeck.wave RUN_ANY_IN_BACKGROUND allow`.
2. Launch `MainActivity`, press Start (or start the service from the activity
   via an intent extra so adb can do it). Confirm in logcat that the camera
   opened at 320x240, which orientation transform was chosen, the landmarker
   initialised and which delegate it took, and that `stats:` lines appear with
   a non-zero fps.
3. Fire the simulation hook for each command and confirm the HUD line and a
   UDP send attempt in the log. Without the phone present, discovery will find
   nothing; that is expected and must be logged as such, not as an error.
4. Measure cost with the service running for one minute:
   `adb shell top -n 1 -b | grep wave` for CPU, and
   `adb shell dumpsys thermalservice | grep "Thermal Status"`. Record both.
5. Do not test gesture recognition with a real hand; there is no hand at the
   desk in this pass. Instead unit test `GestureEngine` with synthetic
   sequences: a clockwise circle of 20 points must emit VOL_UP steps and no
   other command; an anticlockwise one VOL_DOWN; a fast rightward sweep
   NEXT; a held fist PLAY_PAUSE exactly once; a hand that is too small must
   emit nothing. Run these with the Gradle `test` task.

Leave the service running at the end, with autostart on.

## Rules for both execution passes

- Do not `git commit` or `git push`. The verification pass does that.
- Track A edits only `thardeck/companion/`. Track B edits only
  `thardeck/wave/`. Neither touches `docs/` or the root README.
- Only track B may run adb against the tablet. Only track A may run adb
  against the phone, and only if it is present.
- Surgical changes; do not refactor what already works in `companion/`.
- No em dashes or en dashes in any file, including code comments. Check
  before finishing: `grep -rP "[\x{2014}\x{2013}]"` over your directory must
  return nothing.
- No addresses, serials or network names in any committed file. The tablet
  serial above is for adb commands only and must not appear in the repo.
- Every claim in a README must be something you actually ran and saw.
- Finish with a short report: what was built, what was verified and how, the
  measured numbers, and what could not be verified and why.
