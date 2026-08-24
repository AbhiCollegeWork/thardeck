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
- The five mid-drive freezes are localised to the session layer but not yet attributed to a specific cause; capture is now in place to catch the next one

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

[Unreleased]: https://github.com/AbhiCollegeWork/thardeck/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/AbhiCollegeWork/thardeck/releases/tag/v1.1.0
[1.0.0]: https://github.com/AbhiCollegeWork/thardeck/releases/tag/v1.0.0
