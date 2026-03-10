# PacePilot — AI Cycling Coach for Hammerhead Karoo

Real-time coaching alerts during your ride, powered by rules + optional Gemini AI. Built as a native Karoo extension using the official `karoo-ext` SDK.

## What it does

PacePilot watches your power, heart rate, cadence, and workout data in real time, detects what kind of ride you're doing, and delivers contextual coaching cues via Karoo's InRideAlert system.

**Rule-based engine** (works offline, no API key needed):
- 25+ coaching rules across 5 ride modes
- Zone drift detection, interval compliance, cadence monitoring
- Fueling reminders, HR:power decoupling alerts
- Climb pacing, recovery ceiling enforcement
- Late-ride fatigue protection

**AI layer** (optional, needs Gemini API key + internet):
- Gemini 2.0 Flash generates human-sounding cues from live context
- 30-ride rolling history for personalization ("you tend to fade after 90 min")
- Context caching keeps costs near-zero (~$0.01/ride)
- Falls back to rule-based if offline — rider always gets a message

## Ride Modes

| Mode | Detection | Coaching Focus |
|------|-----------|---------------|
| **Workout** | Structured workout loaded | Interval compliance, target power, recovery quality |
| **Endurance** | Steady Z2, low variability | Zone discipline, fueling cadence, pacing consistency |
| **Climb** | Grade > 3%, elevation gain | Power ceiling, cadence management, summit proximity |
| **Adaptive** | No workout, auto-detect | Observes 10 min, then applies endurance/unstructured coaching |
| **Recovery** | User-selected | Strict Z1 ceiling, easy-day enforcement |

## Architecture

```
telemetry (Karoo SDK streams)
  → TelemetryAggregator (power, HR, cadence, GPS, workout)
    → RideContext snapshot (every tick)
      → ModeDetector → CoachingEngine
        → Rules evaluate → CooldownManager filters
          → InRideAlert dispatch (static message)
            → GeminiClient upgrades async (if enabled)
```

Key design decisions:
- **Hybrid AI**: Rules fire instantly (<1ms), AI upgrades the message async if connected
- **No server needed**: Everything runs on-device. Gemini calls go direct to Google's API
- **Cooldown-aware**: Global + per-rule suppression prevents alert fatigue
- **Mode transitions**: Adaptive mode observes first, then delegates to appropriate coaching strategy

## Setup

### Prerequisites
- Android Studio or Gradle CLI
- Hammerhead Karoo 2/3
- GitHub account (for `karoo-ext` SDK access)

### Build

```bash
# 1. Clone
git clone https://github.com/alainchristopher/pacepilot.git
cd pacepilot

# 2. Configure credentials
cp gradle.properties.example gradle.properties
# Edit gradle.properties — add your GitHub username + PAT (read:packages scope)
# Create PAT at: https://github.com/settings/tokens/new

# 3. Build
./gradlew :app:assembleDebug
```

### Install on Karoo

```bash
# Connect via USB or wireless ADB
adb connect <karoo-ip>:5555

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Set Gemini API key (optional — get free key at aistudio.google.com)
adb shell am start -a android.intent.action.VIEW -d "pacepilot://config?gemini_key=YOUR_KEY"
```

### Uninstall

```bash
adb uninstall io.hammerhead.pacepilot.debug
```

## Configuration

Open PacePilot from the Karoo extensions menu:

- **FTP / Max HR** — Override Karoo profile values (or leave blank to use defaults)
- **Coaching alerts** — Enable/disable, set frequency (aggressive ↔ quiet)
- **Fueling reminders** — Time-based with NomRide integration if available
- **AI coaching** — Toggle Gemini 2.0 Flash, enter API key
- **Ride mode** — Auto-detect or force a specific mode

## AI Coaching Details

When enabled, PacePilot uses a two-phase approach:

1. **Instant**: Rule fires → static message shown immediately
2. **Upgrade**: Gemini gets the coaching event + live telemetry + ride narrative + 30-ride history → generates a personalized message → replaces the static one if it arrives before auto-dismiss

The system prompt is cached using Gemini's context caching API, so only the live context (small) is sent per request. Cost is typically <$0.01 per ride.

### What the AI sees
- Current power/HR/cadence zones and trends
- Ride narrative (events so far: zone changes, fueling, climbs)
- 30-ride rolling history (avg power, duration, dominant zones, consistency)
- The specific coaching rule that fired and why

### What the AI does NOT see
- GPS location or route
- Personal information beyond ride data
- Data from other apps

## Testing

```bash
# Run all 93 unit tests
./gradlew :app:testDebugUnitTest --tests "io.hammerhead.pacepilot.*"
```

Tests cover zone calculation, power analysis, workout classification, coaching rules, cooldown logic, mode detection, and ride simulation scenarios.

## Project Structure

```
app/src/main/kotlin/io/hammerhead/pacepilot/
├── ai/                    # Gemini client, context builder, ride narrative
├── coaching/              # Rules engine (workout, endurance, climb, adaptive)
├── detection/             # Ride mode detection and transitions
├── history/               # 30-ride rolling history persistence
├── model/                 # Data classes (RideContext, CoachingEvent, etc.)
├── settings/              # User preferences (SharedPreferences)
├── telemetry/             # Karoo SDK stream aggregation
├── util/                  # Zone calculator, extensions
├── workout/               # Workout tracking and classification
├── MainActivity.kt        # Settings UI (Jetpack Compose)
└── PacePilotExtension.kt  # Main extension service
```

## Tech Stack

- **Kotlin** + Coroutines/Flow for reactive telemetry
- **karoo-ext 1.1.8** — Hammerhead's official extension SDK
- **Jetpack Compose + Material3** — Settings UI
- **OkHttp** — HTTP client for Gemini API
- **kotlinx-serialization** — JSON parsing
- **Timber** — Structured logging
- **Gemini 2.0 Flash** — Optional AI coaching layer

## Contributing

PRs welcome. The coaching rules are pure functions — easy to add new ones:

```kotlin
fun myNewRule(ctx: RideContext): CoachingEvent? {
    if (/* condition not met */) return null
    return CoachingEvent(
        ruleId = RuleId.MY_RULE,
        message = "Your coaching message",
        priority = CoachingPriority.MEDIUM,
        alertStyle = AlertStyle.COACHING,
        suppressIfFiredInLastSec = 300,
    )
}
```

Add it to the relevant `evaluateAll()` function and register the rule ID in `RuleId`.

## License

MIT

## Roadmap

- [ ] Garmin Connect IQ port
- [ ] Companion phone app for cross-platform support
- [ ] TrainingPeaks/Intervals.icu sync
- [ ] NomRide deep integration
- [ ] Climb profile pre-loading from route
- [ ] Post-ride AI summary
