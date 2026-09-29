# Thar Deck Wave

Hand-gesture music control for the dashboard tablet. The tablet's front camera
watches for a hand close to the screen, recognises a few gestures, and relays
them over UDP to the companion app on the phone, which is where the music
actually plays (phone to car over Bluetooth). A small overlay pill on the
tablet confirms each command without taking touch or focus from the Android
Auto receiver underneath.

Package `com.abhi.thardeck.wave`, label "Thar Deck Wave". Java 17, Gradle,
minSdk 31, targetSdk 34, compileSdk 36. Built for a Galaxy Tab S9 FE+ on
Android 16; the APK carries arm64 native code only.

## Gestures

As the driver sees their own hand, held close to the tablet:

| Gesture | Command |
|---|---|
| Index finger circling clockwise | `VOL_UP`, one step per 50 degrees, like a rotary knob |
| Index finger circling anticlockwise | `VOL_DOWN`, same feel |
| Open hand swipe to the driver's right | `NEXT` |
| Open hand swipe to the driver's left | `PREV` |
| Closed fist held still about half a second | `PLAY_PAUSE`, once; open the hand to arm it again |

A hand only counts when its bounding box is at least 0.28 of the frame height
(so a hand on the wheel is ignored) and it has been there for 150 ms. Rotation
has priority while it is active and releases after 400 ms without turning. A
swipe needs 0.30 of the frame sideways within 450 ms, mostly level, and is
followed by a 600 ms cooldown. Volume steps are capped at 8 per second.

The HUD word for the fist is "Play/Pause": the tablet cannot see the phone's
player state, so it names the toggle rather than guessing which way it went.

## How it works

```
CameraX front camera, 320x240, keep-only-latest, AE range capped at 15 fps
  -> throttle: 15 fps with a hand seen in the last 2 s, else 5 fps
  -> presence gate: 40x30 luma difference; no recent hand and no motion
     means the frame stops here and the landmarker stays idle
  -> rotate upright for the current display rotation, then mirror
     (the one place raw pixels become the driver's frame, +x = driver's right)
  -> MediaPipe HandLandmarker, LIVE_STREAM, 1 hand, GPU delegate, CPU fallback
  -> GestureEngine (pure Java state machine, unit tested)
  -> CommandSender (UDP, discovery)  and  HUD overlay
```

| File | Role |
|---|---|
| `engine/GestureEngine.java` | The recognisers. No Android imports. |
| `engine/Tuning.java` | Every threshold, with the defaults above. |
| `Pipeline.java` | Camera, throttle, orientation transform, landmarker, stats, retry. |
| `GestureService.java` | Camera foreground service; owns pipeline, sender, HUD. |
| `CommandSender.java` | Discovery and UDP send. |
| `Hud.java` | The overlay pill and the volume ring. |
| `MainActivity.java`, `CalibView.java` | Status, Start and Stop, calibrate view, tuning sliders, relay settings, autostart. |
| `BootReceiver.java` | Autostart after boot, through MainActivity. |
| `SimReceiver.java` | Simulation hook for testing without a hand. |

The presence gate keeps the landmarker off while the cabin is still. Each
throttled frame (5 fps when idle) has its luma plane sampled on a 40x30 grid,
read in place with one reused buffer, and compared with the previous sample.
The landmarker runs when the mean absolute difference exceeds `motionMinDiff`
(default 6 on the 0..255 scale), or a hand was seen in the last 2 s so
tracking never drops out mid-gesture, or on the first frame after a start so a
model or delegate failure surfaces at once. Motion runs the landmarker on that
same frame; a hand found then lifts the rate to 15 fps as before. The gate is
also held open while the Calibrate view is on screen. Transitions are logged
once each (`gate: motion, landmarker on`, `gate: still, landmarker off`).

The display rotation is followed live (the receiver turns the screen to
landscape when projection starts), so the transform is right whenever the
screen content is upright for the driver.

Any camera or landmarker error releases everything and retries after 2 s,
doubling to 30 s. A watchdog restarts the pipeline if no camera frame arrives
for 10 s.

## Protocol

Shared with the companion app. UDP to the phone on port 5299, one ASCII line
per datagram:

```
TD1 <token> <CMD>        CMD: PING VOL_UP VOL_DOWN PLAY_PAUSE NEXT PREV
TD1 PONG <phone model>   the phone's reply, to PING only
```

The token defaults to `thardeck` and must match on both sides; the phone drops
a wrong token silently.

Discovery, first responder cached for ten minutes: PING the default gateway
(the phone, on its own hotspot), then the subnet broadcast address (a shared
home network), then a manual address if one is set. It re-probes on any Wi-Fi
change. Commands are fire and forget; to notice a phone that has gone, each
command is followed by a PING to the cached phone, and three commands in a row
without a PONG drop the cache so the next command re-probes. With no phone
found, discovery logs `no phone found` at info level and commands are logged as
not sent.

## Build

No wrapper script; use the local Gradle 8.14.3 launcher with JDK 21 on PATH.
Write `local.properties` with `sdk.dir` pointing at your Android SDK first
(gitignored).

```
gradle assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
gradle test              # GestureEngine unit tests
```

The first build downloads the MediaPipe hand model (about 7.8 MB) into
`app/src/main/assets/hand_landmarker.task` through the `downloadHandModel`
task, which runs before `preBuild` when the file is missing. The model is
gitignored and never committed.

## Install and set up

```
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.abhi.thardeck.wave SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +com.abhi.thardeck.wave
adb shell cmd appops set com.abhi.thardeck.wave RUN_ANY_IN_BACKGROUND allow

# start the service and turn autostart on; the screen closes itself
adb shell am start -n com.abhi.thardeck.wave/.MainActivity --ez start true --ez finish true --es auth thardeck --ez autostart true
```

A camera foreground service can only be started while the app is in the
foreground, so the service is always started through MainActivity. The
self-closing launch (`finish true`, used by adb and by the boot receiver) shows
over the lock screen for the second it takes, because in the car the tablet
usually sits on its lock screen. Nothing is unlocked, and the normal launcher
entry does not do this.

MainActivity extras: `start`, `stop` and `finish` (booleans) work as given.
The settings extras, `autostart` (boolean), `token`, `manual` (manual phone
address, empty to clear) and `delegate` (`gpu`, the default, or `cpu`), are
honoured only together with `auth` equal to the current relay token; otherwise
the app logs `extras: bad auth, settings ignored`. The activity is exported, so
without this another app could set a token of its choosing and then pass the
simulation hook's check. A new token takes effect at the next discovery.

## Testing without a hand

```
adb shell am broadcast -p com.abhi.thardeck.wave -a com.abhi.thardeck.wave.SIM --es token thardeck --es cmd NEXT
```

`token` must equal the configured relay token (the same value the sender puts
in every datagram); without it the intent is dropped and `sim: bad token` is
logged. `cmd` is any protocol command and goes straight to the sender and the HUD,
exactly as a recognised gesture would. `cmd PING` re-runs discovery. `cmd SNAP`
saves the next analysed frame, already in the driver's frame, to the app's
`files/snap.png` (read it with `adb exec-out run-as com.abhi.thardeck.wave cat
files/snap.png`), to check the orientation transform by eye. The `-p` is
needed: the receiver is declared in the manifest, so the broadcast must be
addressed to the package. The hook stays exported so adb can reach it; the
token is what keeps other apps on the tablet from driving the phone through it.

Logs are at tag `THARWAVE`: engine transitions and commands, discovery, sends,
PONGs, HUD lines, camera state, the chosen transform, the delegate, gate
transitions, and every five seconds
`stats: analysed=N fps=F infer_ms=M hand=P% gate=on|off motion=AVG/MAX`, where
motion is the gate's reading over those five seconds.

## Tuning

Every threshold lives in `engine/Tuning.java`, including the presence gate's
`motionMinDiff`; the status screen shows the live motion reading next to it.
The app screen has a slider for each, applied live and saved, with a reset to defaults. The Calibrate button
shows the frames the model actually analysed (a mirror view), with the
landmarks, the hand box (green when it passes the size gate), a bar the height
of the gate, and the engine's live state line: state, box height, fist,
fingertip radius, roundness and the rotation accumulator.

## Verified

On the tablet, 29 Sep 2026, at a desk, tablet locked on its screensaver, the
phone on the same home Wi-Fi running the companion relay:

- Unit tests (`gradle test`): 11 of 11 pass. Two check that every tuning key
  reads, writes and resets its own field. The other nine include the five cases the design
  asks for: a 20 point clockwise circle gives only `VOL_UP` (4 steps), an
  anticlockwise one only `VOL_DOWN` (4), a fast rightward sweep exactly one
  `NEXT`, a held fist exactly one `PLAY_PAUSE`, and a hand too small emits
  nothing. A control test runs the same small-hand motions with the size gate
  lowered and they fire, so it is the gate that silences them.
- Camera opened: `camera: open, analysis stream 320x240`, sensor orientation
  270, display rotation 90, AE fps range [15, 15]; the status screen showed
  15.0 fps from the sensor.
- Transform chosen: `rotate 0 clockwise then mirror horizontally, 320x240 ->
  320x240`. A SNAP frame viewed on the PC was upright. Mirroring follows from
  the geometry and was not checked against a real hand.
- Landmarker: `initialised, delegate GPU`, no fallback needed.
- Before the presence gate, stats with no hand in view read
  `analysed=24..25 fps=4.8..5.0 infer_ms=60..73 hand=0%`: the landmarker ran
  at the 5 fps idle rate the whole time.
- With the presence gate, at the same still desk: the first frame after start
  ran the landmarker, then `gate: still, landmarker off`, and the stats read
  `analysed=0 fps=0.0 infer_ms=0 hand=0% gate=off motion=1.1/1.1`. The still
  scene measures about 1.1 against the threshold of 6. The gate woke on two
  real scene changes: the camera's exposure settling at start (motion 6.4) and
  the screen lighting up when the app launched (`gate: motion, landmarker on`,
  then `gate: still, landmarker off` about 200 ms later). No hand was
  available to test the wake from a real gesture.
- Simulation hook, each of `VOL_UP VOL_DOWN NEXT PREV PLAY_PAUSE`: a `hud:`
  line, a `send: <CMD> -> <phone>:5299` line, and a PONG from the phone for
  each. After the token guard was added, the right token still worked, while
  a missing or wrong token logged `sim: bad token, dropped` and sent nothing.
  A settings extra without `auth` logged `extras: bad auth, settings ignored`
  and changed nothing.
- Discovery: the phone answered the broadcast PING (the gateway on a home
  network is the router, which does not answer). With a wrong token the phone
  dropped everything: after three unanswered commands the sender re-probed,
  logged `no phone found` at info level, and logged later commands as not sent.
- HUD window: added as an `APPLICATION_OVERLAY`, 431x120 px at top centre,
  alpha 0.8, not touchable or focusable, removed after 900 ms.
- Cost, one minute each, no hand in view, from `/proc/<pid>/stat`:
  - with the presence gate off (the idle state now): 8.4 CPU seconds per 60 s,
    about 14% of one core (under 2% of the eight). `top` read 16.6% and 13.3%.
  - before the gate, landmarker running at 5 fps: GPU delegate 30.8 s per
    60 s (about 51% of one core), CPU delegate 34.2 s (57%) and slower
    (infer about 95 ms). `top` snapshots of the GPU run read 37 to 67%. Most
    of that time was MediaPipe's GL thread and the Mali driver thread.
  - `dumpsys thermalservice`: `Thermal Status: 0` throughout.

## Limitations

- No real hand has been through the pipeline yet. The recognisers are tested
  only with synthetic landmarks, and the thresholds are the design defaults
  until a calibration session in the car.
- The receiver's frame budget (`Throughput over ... dropped=N, skipped=N`) has
  not been checked with Wave running, because the receiver was not projecting
  during the bench test. That is the acceptance check still to do in the car.
- On the lock screen the system hides the HUD window (verified). Whether it
  shows over the receiver while the receiver covers the lock screen was not
  tested, since the receiver was not in the foreground.
- Autostart after a real reboot, and the Pause and Resume notification
  actions, were not exercised.
- The presence gate threshold was checked only against a still desk. How
  often light and scenery in a moving car wake it, and so the idle cost on the
  road, has not been measured; `motionMinDiff` is on the tuning screen for
  that. The cost with a hand in view (landmarker at up to 15 fps) has not been
  measured either.

## Third-party components

Not our work, used under their licences:

- **MediaPipe Tasks Vision** and the **Hand Landmarker** model
  (`com.google.mediapipe:tasks-vision:0.10.21`, model `hand_landmarker.task`
  float16 v1), Google, Apache License 2.0. It does all the hand tracking.
- **CameraX** (`androidx.camera:camera-camera2` and `camera-lifecycle`
  1.4.2), Android Open Source Project, Apache License 2.0. It does the camera.
- JUnit 4.13.2 for the tests (Eclipse Public License 1.0).
