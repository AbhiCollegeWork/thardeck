# Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Document versioning: the `VERSION` file sets the version stamped on every issued PDF. Per-document **revision letters** (A, B, C…) are derived automatically from git history of that document's source file, so each document tracks its own change count independently of the release version.

- **MAJOR** — the described system changes such that an existing install must be reconfigured
- **MINOR** — new documents, new sections, newly validated findings
- **PATCH** — corrections, clarifications, formatting

## [Unreleased]

### Pending validation
- Hotspot 2.4 GHz band fix has not yet been road-tested over a full drive
- Record-while-projecting feasibility for the planned dash cam is unmeasured

## [1.0.0] — 2026-08-19

First public release. Documents a working, daily-driven installation.

### Added
- `CD-000` Overview — system introduction, bill of materials, project status
- `CD-001` Architecture — topology, connection sequence, drive lifecycle, failure boundaries
- `CD-002` Hardware Specification — component register and measured baselines
- `CD-003` Setup Guide — seven-phase build and commissioning manual
- `CD-004` Audio Chain — ground-loop analysis, isolator trade-off, car-scoped equalisation
- `CD-005` Protocol Notes — reverse-engineered wireless projection behaviour
- `CD-006` Diagnostics — capture tooling and data interpretation
- `CD-007` Troubleshooting — ten faults and six documented dead ends
- `CD-008` Roadmap — planned work, including the smart dash cam
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

[Unreleased]: https://github.com/USERNAME/cardeck/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/USERNAME/cardeck/releases/tag/v1.0.0
