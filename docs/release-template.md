# Release v1.1.0 — 2026-03-20

## Added
- App toggle: master on/off switch from settings UI
- Weight-based carb calculation (reads Karoo rider profile weight)
- Workout-type-aware coaching policies (Sweet Spot, Threshold, VO2max, Over-Under, Endurance Surges, Recovery)
- Pre-climb prep cues and multi-climb effort budgeting
- NomRide, 7Climb, Headwind extension adapters (graceful fallback when absent)
- 3 custom data fields: coaching_status, zone_time_sec, ride_score
- Snooze 15 min + Undo Snooze via BonusActions on alerts
- Alert policy config: minimum gap between alerts, max alerts per hour
- FIT developer-field export for coaching events (reviewable post-ride)
- Active-ride state snapshot every 30s — recovered on service restart
- Post-ride summary and pattern detection
- Mercury-2 AI provider option (1000+ tokens/sec, OpenAI-compatible)
- FIT-file backtest replay in unit test suite

## Changed
- AI layer abstracted behind AiCoachingClient interface (Gemini / Mercury / Off)
- Alert text hard-capped at 30 chars to prevent visual overflow on Karoo screen
- All hardcoded rule messages trimmed to ≤30 chars
- FuelingIntelligence rewritten: intensity-aware (%FTP) + weight correction + climb/workout boost
- TelemetryAggregator reads weight and power zone bounds from Karoo UserProfile
- Settings reads always go to SharedPreferences directly (no stale in-memory cache)
- Settings writes use commit() (synchronous) for crash-safe persistence

## Fixed
- Zone time overcounting (was accumulating on every telemetry tick, not 1 Hz)
- Ride average power and HR in summary were using rolling window instead of true accumulator
- Mode transition to CLIMB_FOCUSED broken by distinctUntilChangedBy — replaced with 1 Hz polling loop
- RideNarrative.onIntervalCompleted never called — auto-detected from phase transitions
- Fueling and drink alerts not firing in CLIMB_FOCUSED mode
- App settings toggle ignored — SharedPreferences read path was using stale StateFlow value
- Double app instance on deep link — MainActivity now singleTop
- Active ride snapshot not cleared on ride end — fixed stale state bleed
- Adapter signal fields missing @Volatile — potential stale reads across threads

## Known Issues
- Wind-adjusted pacing not yet active (Headwind data ingested, coaching logic pending)
- Mercury-2 does not use server-side context caching — full prompt sent every call

## Test Evidence
- `./gradlew compileDebugKotlin` passed
- `./gradlew testDebugUnitTest` passed
- FIT backtest replay (March 15 ride) — fueling alerts verified in CLIMB_FOCUSED mode
- Karoo smoke test passed (start / alerts / stop / no crash)

## APK
- Artifact: `app/build/outputs/apk/debug/app-debug.apk`

---

# Release template for future releases

Copy and fill in below for each new release.

# Release `vX.Y.Z` - YYYY-MM-DD

## Added
-

## Changed
-

## Fixed
-

## Known Issues
-

## Test Evidence
- `./gradlew compileDebugKotlin` passed
- `./gradlew testDebugUnitTest` passed
- Karoo smoke test passed (start/alerts/stop/no crash)

## APK
- Artifact: `app/build/outputs/apk/debug/app-debug.apk`

