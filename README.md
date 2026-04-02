# PacePilot — AI Cycling Coach for Hammerhead Karoo

Real-time coaching alerts during your ride, powered by rules + optional AI. Built as a native Karoo extension using the official `karoo-ext` SDK.

## What it does

PacePilot watches your power, heart rate, cadence, and workout data in real time, detects what kind of ride you're doing, and delivers contextual coaching cues via Karoo's InRideAlert system.

**Rule-based engine** (works offline, no API key needed):
- 25+ coaching rules across 5 ride modes
- Zone drift detection, interval compliance, cadence monitoring
- Weight-based fueling reminders (reads weight from Karoo profile)
- HR:power decoupling, climb pacing, multi-climb effort budgeting
- Pre-climb prep cues, recovery ceiling enforcement
- Late-ride fatigue protection

**AI layer** (optional, needs API key + internet):
- **Gemini 2.0 Flash** — recommended, context caching, ~$0.01/ride
- **Mercury-2** — experimental, 1,000+ tokens/sec via Inception Labs (10M free tokens)
- 30-ride rolling history for personalization
- Falls back to rule-based if offline — rider always gets a message

**Integrations** (graceful fallback when absent):
- **NomRide** — carb balance, burn rate, carbs eaten → deficit-aware fueling
- **7Climb** — distance to summit, climb number → pre-climb prep + effort budget
- **Headwind** — wind speed, relative wind → context for future wind pacing

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
Karoo streams + NomRide/7Climb/Headwind (when present)
  → TelemetryAggregator (power, HR, cadence, workout, weight, carbs)
    → RideContext snapshot (every tick)
      → ModeDetector → CoachingEngine
        → Rules evaluate → CooldownManager filters
          → InRideAlert dispatch (static message)
            → Gemini or Mercury upgrades async (if enabled)
```

Key design decisions:
- **Hybrid AI**: Rules fire instantly (<1ms), AI upgrades the message async if connected
- **No server needed**: Everything runs on-device. Gemini calls go direct to Google's API
- **Cooldown-aware**: Global + per-rule suppression prevents alert fatigue
- **Mode transitions**: Adaptive mode observes first, then delegates to appropriate coaching strategy

## Setup

### Just want to ride?

Download the APK from the [releases page](https://github.com/alainchristopher/pacepilot/releases) and skip to **Install on Karoo** below.

### Build from source

Requires Android Studio or Gradle CLI and a GitHub account (the `karoo-ext` SDK is hosted on GitHub Packages).

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

There are three ways to install. Option A is the easiest.

#### Option A — Hammerhead Companion app (easiest, no computer needed)

1. Download the APK to your phone from the [releases page](https://github.com/alainchristopher/pacepilot/releases)
2. Open the **Hammerhead Companion app** on your phone
3. Go to **Extensions → Manage → Upload APK** (or use the share sheet to share the APK file to the Companion app)
4. On iPhone you can also **AirDrop** the APK to your phone first, then share it to the Companion app from Files

The Companion app will push the APK to your Karoo over Bluetooth automatically.

#### Option B — ADB via USB

Enable developer mode on Karoo first: **Settings → About → tap Build number 7× → Developer options → USB debugging ON**

```bash
# Mac: install ADB
brew install android-platform-tools

# Plug in via USB, allow debugging when Karoo prompts, then:
adb install -r PacePilot-v1.1.0.apk
```

Windows: download [platform-tools](https://developer.android.com/tools/releases/platform-tools), unzip, run `adb install -r` from that folder.

#### Option C — ADB via Wi-Fi

```bash
# Enable Wireless Debugging on Karoo (Developer options), note the IP shown
adb connect <karoo-ip>:5555
adb install -r PacePilot-v1.1.0.apk
```

#### Set API key via deep link (optional, ADB only)

```bash
adb shell am start -a android.intent.action.VIEW -d "pacepilot://config?gemini_key=YOUR_KEY"
# Or for Mercury-2: pacepilot://config?mercury_key=YOUR_KEY&provider=mercury
```

### Hotspot tip

Karoo disconnects from Wi-Fi when a ride starts. To keep internet connected for AI coaching:

1. **Start your ride on Karoo first**
2. Then enable hotspot on your phone
3. Karoo reconnects automatically within ~30 seconds

Enabling hotspot before starting the ride will not work — Karoo drops Wi-Fi on recording start.

### Uninstall

```bash
adb uninstall io.hammerhead.pacepilot.debug   # debug build
# adb uninstall io.hammerhead.pacepilot       # release build
```

## Configuration

Open PacePilot from the Karoo extensions menu:

- **App toggle** — Master on/off (PacePilot Active)
- **FTP / Max HR** — Override Karoo profile (or use weight/HR from Karoo profile)
- **Coaching alerts** — Enable/disable, fueling reminders, alert frequency
- **Alert policy** — Min gap between alerts, max alerts per hour
- **AI coaching** — Choose provider: Gemini 2.0 Flash, Mercury-2, or Off
- **Snooze / Undo** — 15-min snooze or undo via BonusActions on alerts

## AI Coaching Details

When enabled, PacePilot uses a two-phase approach:

1. **Instant**: Rule fires → static message shown immediately
2. **Upgrade**: AI gets the coaching event + live telemetry + ride narrative + 30-ride history → generates a personalized message → replaces the static one if it arrives before auto-dismiss

**Gemini 2.0 Flash** (recommended): Context caching keeps costs ~$0.01/ride. Free key at [aistudio.google.com](https://aistudio.google.com).

**Mercury-2** (experimental): OpenAI-compatible API via [platform.inceptionlabs.ai](https://platform.inceptionlabs.ai). 10M free tokens. ~1,000+ tokens/sec. No server-side cache — full context sent per call.

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
# Run all unit tests
./gradlew :app:testDebugUnitTest
```

Tests cover zone calculation, power analysis, workout classification, coaching rules, cooldown logic, mode detection, ride simulation scenarios, and FIT-based backtest replay.

## Project Structure

```
app/src/main/kotlin/io/hammerhead/pacepilot/
├── ai/                    # Gemini/Mercury clients, context builder, ride narrative
├── coaching/              # Rules engine (workout, endurance, climb, adaptive)
├── detection/             # Ride mode detection and transitions
├── fields/                # Custom data fields (coaching_status, zone_time, ride_score)
├── fit/                   # FIT developer-field export for coaching events
├── history/               # 30-ride history, PostRideIntelligence, RideSummaryBuilder
├── integrations/          # NomRide, 7Climb, Headwind adapters
├── model/                 # Data classes (RideContext, CoachingEvent, etc.)
├── settings/              # User preferences (SharedPreferences)
├── state/                 # Active-ride state snapshot and restore
├── telemetry/             # Karoo SDK stream aggregation, FuelingIntelligence
├── util/                  # Zone calculator, extensions
├── workout/               # Workout tracking and classification
├── MainActivity.kt        # Settings UI (Jetpack Compose)
└── PacePilotExtension.kt  # Main extension service
```

## Tech Stack

- **Kotlin** + Coroutines/Flow for reactive telemetry
- **karoo-ext 1.1.8** — Hammerhead's official extension SDK
- **Jetpack Compose + Material3** — Settings UI
- **OkHttp** — HTTP client for Gemini/Mercury APIs
- **kotlinx-serialization** — JSON parsing
- **Timber** — Structured logging
- **Gemini 2.0 Flash** or **Mercury-2** — Optional AI coaching layer

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

### v1.1 — shipped ✓
- App toggle (master on/off)
- Weight-based carb calculation from Karoo profile
- Workout-type-aware coaching (Sweet Spot / Threshold / VO2max)
- Fueling + drink reminders in climb mode (was missing)
- Pre-climb prep cues + multi-climb effort budgeting
- NomRide, 7Climb, Headwind adapters (graceful fallback)
- 3 custom data fields: coaching_status, zone_time_sec, ride_score
- Snooze 15 min + Undo Snooze BonusActions
- Alert policy: min gap + max per hour
- FIT developer-field export for coaching events
- Active-ride state snapshot every 30s — restored on service restart
- Post-ride summary + pattern detection
- Mercury-2 AI provider alongside Gemini 2.0 Flash
- FIT-file backtest replay in unit test suite

### v1.2 — planned
- Wind-adjusted pacing (Headwind data already ingested, coaching logic pending)
- Finish-line / negative-split strategy mode
- Log Fuel BonusAction (in-ride carb logging without NomRide)
- Hammerhead Extension Library submission

### Later
- Companion phone app for post-ride review
- Intervals.icu / TrainingPeaks plan sync
