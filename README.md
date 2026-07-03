# PacePilot — AI Cycling Coach for Hammerhead Karoo

Real-time coaching alerts during your ride, powered by rules + optional AI. Built as a native Karoo extension using the official `karoo-ext` SDK.

**Latest release: [v2.0.0](https://github.com/alainchristopher/pacepilot/releases/tag/v2.0.0)** — offline-first coaching + Race Mode for triathlon.

## What it does

PacePilot watches your power, heart rate, cadence, and workout data in real time, detects what kind of ride you're doing, and delivers contextual coaching cues via Karoo's InRideAlert system.

**Rule-based engine** (works fully offline, no API key needed):
- 45+ coaching rules across **6 ride modes**
- Rotating offline message pools — cues vary ride-to-ride instead of repeating one string
- Zone drift detection, interval compliance, cadence monitoring
- Weight-based fueling reminders (reads weight from Karoo profile)
- HR:power decoupling, climb pacing, multi-climb effort budgeting
- Pre-climb prep cues, recovery ceiling enforcement
- Late-ride fatigue protection

**Offline cue bank** (optional, needs API key + Wi-Fi once before the ride):
- Tap **Prepare offline coach** in settings while on Wi-Fi
- AI generates ~80–120 personalized cues tailored to your 30-ride history
- Stored on device — on race day with no hotspot, messages still feel personal
- Falls back to rotating pools → rule defaults if a cue isn't found

**Race Mode** (manual or `mode_race` BonusAction):
- Target power band from IF or explicit watts
- Run-leg protection when NP drifts above plan
- Negative split guidance (first 40 km slightly under target)
- Finish-line cues at 75% and 90% of route distance
- VI watchdog — steady power enforcement
- Race-specific fuel/drink cadence with T2 prep

**AI layer** (optional, needs API key + internet during ride):
- **Gemini 2.0 Flash** — recommended, context caching, ~$0.01/ride
- **Mercury-2** — experimental, 1,000+ tokens/sec via Inception Labs (10M free tokens)
- 30-ride rolling history for personalization
- **✦ indicator** on alert title when AI has upgraded the message
- Message priority: cue bank → rotating pool → rule default → AI upgrade (async)

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
| **Recovery** | User-selected | Strict Z1 ceiling, easy-day enforcement + fuel/drink cadence |
| **Race** | User-selected / BonusAction | Power plan, run protection, finish-line, VI, race fueling |

## Custom Data Fields

Add these from the Karoo data field picker:

| Field | Values | Meaning |
|-------|--------|---------|
| **coaching_status** | 0 / 1 / 2 / 3 / 9 | off / rules / cue bank / AI / snoozed |
| **zone_time_sec** | seconds | Time in current power zone this ride |
| **ride_score** | 0–100 | Compliance / fueling score |
| **race_delta** | watts | Signed delta vs race target (Race mode only) |

## Architecture

```
Karoo streams + NomRide/7Climb/Headwind (when present)
  → TelemetryAggregator (power, HR, cadence, workout, weight, carbs)
    → RideContext snapshot (every tick)
      → ModeDetector → CoachingEngine
        → Rules evaluate → CooldownManager filters
          → MessageResolver (cue bank → pool → default)
            → InRideAlert dispatch
              → Gemini or Mercury upgrades async (if enabled)
```

Key design decisions:
- **Offline-first**: Rules + pools fire instantly; cue bank adds personalization without internet
- **Hybrid AI**: Rules fire instantly (<1ms), AI upgrades the message async if connected
- **No server needed**: Everything runs on-device. API calls go direct to Google/Inception
- **Cooldown-aware**: Global + per-rule suppression prevents alert fatigue
- **Live settings**: Changes apply mid-ride without restarting the extension

## Setup

### Just want to ride?

Download **`PacePilot-v2.0.0-debug.apk`** from the [releases page](https://github.com/alainchristopher/pacepilot/releases/latest) and skip to **Install on Karoo** below.

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

1. Download the APK to your phone from the [releases page](https://github.com/alainchristopher/pacepilot/releases/latest)
2. Open the **Hammerhead Companion app** on your phone
3. Go to **Extensions → Manage → Upload APK** (or use the share sheet to share the APK file to the Companion app)
4. On iPhone you can also **AirDrop** the APK to your phone first, then share it to the Companion app from Files

The Companion app will push the APK to your Karoo over Bluetooth automatically.

#### Option B — ADB via USB

Enable developer mode on Karoo first: **Settings → About → tap Build number 7× → Developer options → USB debugging ON**

```bash
# Mac: install ADB
brew install android-platform-tools

# Download from releases, then:
adb install -r PacePilot-v2.0.0-debug.apk
```

Windows: download [platform-tools](https://developer.android.com/tools/releases/platform-tools), unzip, run `adb install -r PacePilot-v2.0.0-debug.apk` from that folder.

#### Option C — ADB via Wi-Fi

```bash
# Enable Wireless Debugging on Karoo (Developer options), note the IP shown
adb connect <karoo-ip>:5555
adb install -r PacePilot-v2.0.0-debug.apk
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

For **race day without hotspot**: use **Prepare offline coach** the night before while on Wi-Fi. You'll get personalized cues from the on-device cue bank.

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
- **FUELING** — Carb target g/h, carbs per serving, fuel threshold, drink interval
- **ADVANCED** — Climb route gradient/gain thresholds, min effort cadence
- **RACE** — Target IF or watts, duration, distance, carb g/h, event name, enable toggle
- **AI coaching** — Choose provider: Gemini 2.0 Flash, Mercury-2, or Off
- **Prepare offline coach** — Generate on-device cue bank (Wi-Fi required, run before race/training camp)
- **Coaching language** — EN, DE, FR, NL, ES, IT, PT, DA, SV, NO. AI translates cues into your language
- **Post-ride intelligence** — Summary, patterns, and timeline from your last ride
- **Analytics** — Opt-in anonymous usage data (improves the app)
- **Snooze / Undo** — 15-min snooze or undo via BonusActions on alerts

### Race Mode quick start

1. Configure **RACE** card (e.g. IF 0.82, 90 km, 75 g/h carbs, enable)
2. Save settings
3. On the bike: force mode **Race** (settings chip) or use **Mode: Race** BonusAction
4. Add **race_delta** and **coaching_status** data fields to your race profile

### Ride analysis (optional, on your computer)

```bash
pip install -r tools/requirements.txt
python tools/analyze_rides.py --karoo-adb --output docs/fitness_report.md
```

Pulls FIT files from Karoo via ADB, writes a fitness report + `tools/ride_history_seed.json` + `tools/race_plan_seed.json`.

## AI Coaching Details

When enabled, PacePilot uses a two-phase approach:

1. **Instant**: Rule fires → MessageResolver picks cue bank / pool / default → shown immediately
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

Tests cover zone calculation, power analysis, workout classification, coaching rules (including Race Mode), cooldown logic, mode detection, ride simulation scenarios, and 90 km race-leg replay.

## Project Structure

```
app/src/main/kotlin/io/hammerhead/pacepilot/
├── ai/                    # Gemini/Mercury clients, CueBankGenerator, ride narrative
├── coaching/              # Rules engine + OfflineMessagePools, MessageResolver, RaceCoachingRules
├── detection/             # Ride mode detection and transitions
├── fields/                # Custom data fields (coaching_status, zone_time, ride_score, race_delta)
├── fit/                   # FIT developer-field export for coaching events
├── history/               # Ride history, cue bank, race plan, PostRideIntelligence
├── integrations/          # NomRide, 7Climb, Headwind adapters
├── model/                 # Data classes (RideContext, CoachingEvent, RideMode, etc.)
├── settings/              # User preferences (SharedPreferences)
├── state/                 # Active-ride state snapshot and restore
├── telemetry/             # Karoo SDK stream aggregation, FuelingIntelligence
├── ui/                    # PacePilotTheme (Compose palette)
├── util/                  # Zone calculator, extensions
├── workout/               # Workout tracking and classification
├── MainActivity.kt        # Settings UI (Jetpack Compose)
└── PacePilotExtension.kt  # Main extension service

tools/
├── analyze_rides.py       # FIT analysis, fitness report, race/history seeds
└── requirements.txt
```

## Tech Stack

- **Kotlin** + Coroutines/Flow for reactive telemetry
- **karoo-ext 1.1.8** — Hammerhead's official extension SDK
- **Jetpack Compose + Material3** — Settings UI
- **OkHttp** — HTTP client for Gemini/Mercury APIs
- **kotlinx-serialization** — JSON parsing (history, cue bank, race plan)
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

Add it to the relevant `evaluateAll()` function, register the rule ID in `RuleId`, and add variants to `OfflineMessagePools`.

## License

MIT

See [CHANGELOG.md](CHANGELOG.md) for version history.

## Roadmap

### v2.0 — shipped ✓ ([release](https://github.com/alainchristopher/pacepilot/releases/tag/v2.0.0))
- **Offline-first coaching** — rotating message pools + pre-ride AI cue bank
- **Race Mode** — triathlon bike-leg rules: power band, run protection, finish-line, negative split, VI watchdog, race fueling
- **RACE / FUELING / ADVANCED** settings cards; `mode_race` BonusAction; `race_delta` data field
- **`tools/analyze_rides.py`** — FIT analysis, fitness report, race/history seeds
- Post-ride timeline; live settings; CI unit tests

### v2.1 — planned
- Wind-adjusted pacing (Headwind data already ingested, coaching logic pending)
- Hammerhead Extension Library submission

### Earlier releases
- **v1.3** — Coaching language (10 langs), AI ✦ indicator
- **v1.2** — Auto-ack fueling, CI, analytics opt-in
- **v1.1** — 5 modes, hybrid AI, NomRide/7Climb/Headwind, 3 data fields

### Later
- Companion phone app for post-ride review
- Intervals.icu / TrainingPeaks plan sync
