# Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Document versioning: the `VERSION` file sets the version stamped on every issued PDF. Per-document **revision letters** (A, B, C…) are derived automatically from git history of that document's source file, so each document tracks its own change count independently of the release version.

- **MAJOR** - the described system changes such that an existing install must be reconfigured
- **MINOR** - new documents, new sections, newly validated findings
- **PATCH** - corrections, clarifications, formatting

## [Unreleased]

### Pending validation
- Record-while-projecting feasibility for the planned dash cam is unmeasured
- The 3.3.0 corruption fix is bench-verified but has not yet run a full drive
- Projection can currently start outside the car; gating is designed but not implemented, see TD-007 section 15
- Native mode would remove the head unit server entirely and gate on the car's Bluetooth, solving both the manual start and the false-trigger problem. Not attempted: it needs a Bluetooth restart and re-pair.

## [1.4.0] - 2026-09-04

Receiver updated to 3.3.0. One bug we filed came back fixed, and one long-standing assumption turned out to be wrong.

### Fixed
- **Video corruption that persisted for thirty to forty-five seconds is resolved upstream.** Open Headunit 3.3.0 paces the transport thread instead of shedding reference frames, which removes the cause, and adds concealment that freezes the last good frame and forces a keyframe rather than melting through the damage. The policy caps concealment at 3500 ms with a slowest observed repair of 2780 ms. Documented in TD-007 section 16.
- **Upstream #912 is fixed and closed.** The Bluetooth keyboard crash we filed shipped as a fix in 3.3.0. TD-007 section 14 now records this rather than describing an open bug.

### Added
- **TD-007 section 16**, part of the screen smears and stays smeared. Includes the diagnostic rule that the corrupt region is the region nothing is repainting, which is what identifies a lost reference frame rather than a link fault.
- **TD-007 section 17**, orientation silently reverts. `ignoreOrientationRequest` returned to `true` twice with no reboot in between, producing portrait geometry and a zero frame rate with no error anywhere.
- **TD-003 section 3.1a**, turning off adaptive Wi-Fi on the phone. Samsung's switch-to-better-network behaviour moves the phone off the link mid-session. Also records that the hotspot subnet is not stable across sessions and must never be hardcoded.

### Changed
- **Corrected: the head unit server does not last until the phone reboots.** It dies with Android Auto's `:car` process, which is recycled independently. Measured with the phone up 16 days and the hosting process 1 day 4 hours old, which is when the head unit stopped connecting. Android Auto is already in the device idle whitelist at standby bucket 5, so this is neither doze nor app standby and no further exemption prevents it. TD-005 and TD-001 corrected; the previous "one action per phone reboot" claim was wrong.
- Receiver version recorded as 3.3.0 in TD-002.

### Measured
- 1080p, HEVC hardware decoder: `rendered=150 (30fps), fed=150, dropped=0, skipped=0, concealed=0`, decode latency 20 ms, p95 40 ms.
- The same link at a higher resolution setting ran at 52 to 53 fps, so **lowering the resolution did not raise the frame rate**.

## [1.3.0] - 2026-08-30

The wireless path changed completely, because Google removed the one this system was built on.

### Changed
- **Android Auto 17.3 removed the connection intent that helper apps used.** Helper-based wireless setups no longer work and cannot be fixed by configuration. Confirmed by the Open Headunit maintainer in upstream #908 and reproduced here by A/B: the identical command on the identical topology worked on 17.2 and does nothing on 17.4, with the phone never opening a socket.
- **The system now uses Android Auto's own head unit server on TCP 5277.** The head unit dials the phone rather than the reverse. Connects in about 2 seconds at 50 fps with no dropped frames. One action per phone reboot, not per drive.
- **The helper app has been removed.** A/B tested first: connect time, reconnect time and frame rate were identical with and without it, and it survives park, resume and phone deep doze unchanged. It was also holding a permanent search state and drawing power for nothing.
- Receiver updated to 3.2.6. Projection raised to 2K, which switches the decoder to HEVC.

### Added
- `TD-007` section 13: nothing connects after an Android Auto update, including the one-command test for whether the phone is even trying, and a warning not to uninstall Android Auto's updates on Samsung hardware
- `TD-007` section 14: projection dies when a Bluetooth keyboard connects, filed upstream as #912
- `TD-007` section 15: projection starting when you are not driving, and why the disambiguation problem returned through a different door
- `TD-005`: the 17.3 change, the head unit server, and what the server survives

### Reported upstream
- [#912](https://github.com/andreknieriem/open-headunit/issues/912) `AapProjectionActivity` does not declare `keyboard` or `keyboardHidden` in configChanges, so a Bluetooth keyboard connecting destroys the activity and kills the session
- [#913](https://github.com/andreknieriem/open-headunit/issues/913) no alphabetic keycodes are advertised, so text entry falls back to the phone keyboard

### Fixed
- Pixel density had been reset to 172 by the app update, which made the projected UI look badly zoomed. Restored to 218.
- Auto-update disabled for Android Auto, so the system cannot break itself overnight again

## [1.2.0] - 2026-08-29

Both open faults identified, on the first run of the two-ended instrumentation.

### Fixed
- **Mid-drive freezes: cause found.** The receiver app is killed by the system while projecting. The process ID changes at every session drop and is sometimes absent, and the system log records it dying as a foreground service, with 16 restarts in a single captured log. Doze exemption alone is not enough; the app also needs exemption from app standby and from vendor battery management.
- **Car icon and heading arrow missing: cause found.** Battery Saver was enabled on the phone, throttling location to a 100 metre network fix. Android Auto takes position from the phone and the receiver declares no position sensor, so there is no fallback. Fixed by disabling Battery Saver and exempting Maps and Android Auto from battery optimisation.

### Added
- `TD-007` section 12: distinguishing a map that has stopped following from a genuinely frozen session. They look alike and have unrelated causes.
- Location provider, accuracy and fix age are now recorded on the phone every 5 seconds

### Fixed in tooling
- The phone watcher reported Android Auto as dead for entire drives because `pidof` was matching the wrong process name. A probe that silently returns nothing reads as evidence, which is worse than having no probe.

## [1.1.0] - 2026-08-24

### Confirmed
- **The video corruption fix holds.** Nine days and twelve drives on the separated band: hotspot at 5745 MHz with the tablet's home network elsewhere, Rx 433 to 866 Mbps, and zero contention stalls. The co-channel collision described in TD-007 section 2 has not recurred.
- Car audio noise reduced to zero at source, and the bass loss from the ground-loop isolator recovered using the amplifier sensitivity and low-pass trim-pots described in TD-002

### Added
- `TD-007` section 11: mid-drive freezes with a healthy radio link, including how to tell a freeze from corruption, since the two look similar and have opposite causes
- `scripts/tab-watch.sh` and `scripts/phone-watch.sh`: supervised, two-ended capture
- `TD-006`: a section on supervised capture and why unsupervised logging is worthless

### Fixed
- Log capture no longer dies silently. The previous unsupervised `logcat` was reaped by the system and went unnoticed for five days, leaving a run of freezes unrecorded. Capture is now watchdogged and filtered.
- Instrumentation now covers the phone as well as the tablet. Only recording the receiver could prove the network was healthy but never say what ended the session.

## [1.0.0] - 2026-08-19

First public release. Documents a working, daily-driven installation.

### Added
- `TD-000` Overview - system introduction, bill of materials, project status
- `TD-001` Architecture - topology, connection sequence, drive lifecycle, failure boundaries
- `TD-002` Hardware Specification - component register and measured baselines
- `TD-003` Setup Guide - seven-phase build and commissioning manual
- `TD-004` Audio Chain - ground-loop analysis, isolator trade-off, car-scoped equalisation
- `TD-005` Protocol Notes - reverse-engineered wireless projection behaviour
- `TD-006` Diagnostics - capture tooling and data interpretation
- `TD-007` Troubleshooting - ten faults and six documented dead ends
- `TD-008` Roadmap - planned work, including the smart dash cam
- Diagnostic tooling: drive logging start/pull/stop scripts and the on-device Wi-Fi sampler
- PDF build pipeline producing controlled, versioned documents with git-derived revision history

### Fixed in the system being documented
- Discovery deadlock between mismatched connection strategies
- Video corruption caused by STA+AP co-channel collision
- Audio incorrectly routing to the tablet instead of the car stereo
- Orientation ignored on Android 12L+ large-screen devices
- Projected UI too small for safe use while driving
- Receiver app self-launching outside the car
- Bass loss introduced by the ground-loop isolator

[Unreleased]: https://github.com/AbhiCollegeWork/thardeck/compare/v1.3.0...HEAD
[1.3.0]: https://github.com/AbhiCollegeWork/thardeck/releases/tag/v1.3.0
[1.2.0]: https://github.com/AbhiCollegeWork/thardeck/releases/tag/v1.2.0
[1.1.0]: https://github.com/AbhiCollegeWork/thardeck/releases/tag/v1.1.0
[1.0.0]: https://github.com/AbhiCollegeWork/thardeck/releases/tag/v1.0.0
