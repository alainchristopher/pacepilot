# Changelog

All notable changes to PacePilot are documented here.

## [2.0.0] — 2026-07-03

### Added
- **Offline-first coaching** — rotating message pools (3–5 variants per rule) so offline cues don't repeat verbatim
- **Pre-ride AI cue bank** — "Prepare offline coach" generates personalized cues on Wi-Fi; served offline on race day
- **Race Mode** — triathlon bike-leg coaching: power band, run-leg protection, negative split, finish-line (75%/90%), VI watchdog, race fueling
- **RACE settings card** — target IF/watts, duration, distance, carb g/h, enable toggle
- **`mode_race` BonusAction** — switch to Race mode from any alert
- **`race_delta` data field** — watts above/below race target (NP-based)
- **FUELING / ADVANCED settings** — carb target, serving size, fuel threshold, drink interval, climb thresholds, min cadence
- **Post-ride timeline** — last ride coaching summary in settings
- **`tools/analyze_rides.py`** — Karoo FIT + Strava export → fitness report, ride history seed, race plan seed
- **`coaching_status` source indicator** — 0=off, 1=rules, 2=cue bank, 3=AI, 9=snoozed

### Fixed
- Drink reminder interval now respected everywhere (was hardcoded 20 min in fuel sip suffix)
- Mid-ride settings apply live (SharedPreferences listener → cooldown + AI refresh)
- Recovery mode gets fuel/drink cadence
- Race plan and cue bank reload from disk without service restart

### Changed
- Version bump to 2.0.0 (versionCode 10)
- CI runs unit tests on every push

## [1.3.5] — 2026-04-26

- Mercury-2 reliability: 30s timeout, robust response parsing
- Test AI Connection button in settings

## [1.3.0] — 2026-04-25

- Coaching language (10 languages)
- AI indicator (✦) on upgraded alerts

## [1.2.0] — 2026-04-02

- Auto-acknowledge fueling
- GitHub Actions CI
- Analytics opt-in (PostHog)

## [1.1.0]

- Initial public release: 5 ride modes, hybrid AI, NomRide/7Climb/Headwind integrations

[2.0.0]: https://github.com/alainchristopher/pacepilot/releases/tag/v2.0.0
[1.3.5]: https://github.com/alainchristopher/pacepilot/releases/tag/v1.3.5
