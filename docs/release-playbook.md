# PacePilot Release Playbook

## Cadence

- Internal validation build: weekly
- Public beta tag: every 2 weeks
- Stable release: after ride validation gate

## Versioning

- Format: `vMAJOR.MINOR.PATCH`
- Rules:
  - MAJOR: breaking behavior or architecture shift
  - MINOR: feature additions
  - PATCH: fixes/polish only

## Required Gates Before Tag

1. `./gradlew compileDebugKotlin`
2. `./gradlew testDebugUnitTest`
3. Install + smoke test on Karoo:
   - start ride
   - receive alerts
   - stop ride
   - no crash
4. Verify release checklist in `scripts/release.sh`.

## Changelog Discipline

Each release must include:
- Added
- Changed
- Fixed
- Known Issues

Use `docs/release-template.md` as source.

## Beta Feedback Loop

For each beta:
- Track:
  - alert relevance
  - snooze rate
  - crash reports
  - top 3 friction points
- Convert feedback into:
  - hotfix patch candidates
  - next minor priorities

