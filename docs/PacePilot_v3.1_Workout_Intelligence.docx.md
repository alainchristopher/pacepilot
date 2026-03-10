  
**PACEPILOT**

AI Live Coaching for Karoo

*v3.1 — Workout Intelligence \+ Smart Mode Detection*

March 2026  —  ACB Consulting  —  Alain

**CONFIDENTIAL**

# **1\. The Product in One Sentence**

*"PacePilot is the coaching brain for Karoo — it reads your workout, your body, your nutrition, and the terrain, and tells you the one thing you need to do right now."*

It builds on top of the best open-source Karoo extensions (7Climb for climbing, NomRide for nutrition, karoo-headwind for weather) and adds what none of them have: contextual coaching intelligence that adapts to your training intent.

# **2\. Smart Mode Detection: No More Manual Selection**

The v2 and v3 concepts required the rider to manually select a ride mode before each ride. That’s friction, and it’s unnecessary. PacePilot should be smart enough to detect intent automatically.

## **2.1 The detection cascade**

PacePilot evaluates context in this order at ride start and adapts dynamically:

| Priority | Signal | Result | How it works |
| :---- | :---- | :---- | :---- |
| 1 (highest) | Structured workout active on Karoo | WORKOUT mode | Detect via RideState/workout state from KarooSystemService. Read interval targets, durations, zones. Coach through the session. |
| 2 | Route loaded with significant climbing | CLIMB-FOCUSED mode | Analyze route elevation profile. If \>1000m gain or \>30% of route is \>4% gradient, activate climb-aware coaching with 7Climb integration. |
| 3 | Route loaded, mostly flat/rolling | ENDURANCE mode | Default coaching for long rides: drift prevention, fueling discipline, pacing consistency. |
| 4 | No route, no workout | ADAPTIVE mode | Infer from first 10 minutes of riding: zone 1-2 steady \= endurance, high variability \= unstructured/group ride. Adjust dynamically. |

**Override always available:** The rider can force a mode in settings or via BonusAction mid-ride. The smart detection is the default, not a prison.

## **2.2 Dynamic mode transitions**

Modes aren’t static for the whole ride. PacePilot transitions contextually:

* WORKOUT mode → finishes all intervals → transitions to ENDURANCE for cooldown and ride home

* ENDURANCE mode → enters a climb detected by 7Climb → temporarily activates CLIMB coaching → reverts on descent

* ADAPTIVE mode → detects sustained Z1 for 15+ minutes → assumes recovery ride, coaches accordingly

# **3\. Workout Intelligence: Coaching Through Structured Sessions**

This is the highest-value feature. Karoo shows your workout targets as numbers and bars. PacePilot coaches you through them like a sports director in your ear.

## **3.1 How PacePilot reads workouts**

There are two paths depending on what the karoo-ext SDK exposes:

**Path A: Karoo exposes workout state via SDK (preferred)**

If KarooSystemService provides workout events (current interval, target power/HR, time remaining), PacePilot subscribes directly. This is the cleanest integration — zero configuration, instant awareness.

**Path B: Direct TrainingPeaks/Intervals.icu API integration**

If workout state isn’t available via the SDK, PacePilot pulls today’s scheduled workout directly via the TrainingPeaks API or intervals.icu API before the ride. Parse the workout structure (warmup, intervals, recovery, cooldown), match against ride elapsed time, and coach accordingly. This requires a one-time account connection in settings and pre-ride WiFi sync.

*Both paths need to be investigated during the first days of development. Path A is preferable. Path B is the fallback.*

## **3.2 Workout coaching rules**

These are the coaching prompts PacePilot generates during structured sessions, organized by interval phase:

**Pre-interval**

| Trigger | Message example |
| :---- | :---- |
| Effort block starts in 60–90 seconds | "Effort in 60 sec. Get ready." |
| Effort block approaching \+ NomRide shows carb deficit | "Fuel now. Hard effort coming." |
| First high-intensity interval of the session | "First effort block. Settle into it — don’t overcook." |

**During effort intervals**

| Trigger | Message example |
| :---- | :---- |
| Power \>10% above target ceiling for 30+ seconds | "Ease back. 15W above target." |
| Power \>10% below target floor for 30+ seconds | "Push slightly. 20W below target." |
| Power in target range | "Good. Hold." |
| 30 seconds remaining in interval | "30 sec left. Hold." |
| Cadence dropping below 80rpm during effort | "Spin lighter. Cadence dropping." |
| HR ceiling exceeded (if HR-based workout) | "HR too high. Back off slightly." |

**During recovery intervals**

| Trigger | Message example |
| :---- | :---- |
| Power still in Z3+ during recovery | "Actually recover. Drop to Z1." |
| HR not dropping after 60 seconds of recovery | "HR still high. Extend easy spinning." |
| Recovery going well, next effort approaching | "Recovering well. Next effort in 2 min." |
| NomRide shows opportunity to fuel | "Good time to fuel. Take a gel." |

**Session-level intelligence**

| Trigger | Message example |
| :---- | :---- |
| Last interval set | "Final block. You have the fitness." |
| Completing interval quality declining (power fading) | "Power fading. Consider stopping after this rep." |
| HR recovery between intervals getting slower across sets | "Recovery slowing. Cut the session or reduce intensity." |
| All intervals completed | "Session done. Nice work. Fuel within 20 min." |
| Rider skips/extends interval via Karoo controls | Acknowledge and adapt: "Interval skipped. Adjusting." |

## **3.3 Workout type awareness**

Different workout structures need different coaching patterns:

| Workout type | Detection | Coaching emphasis |
| :---- | :---- | :---- |
| Sweet spot / tempo | Sustained Z3-Z4 intervals \>8 min | Pacing consistency. "Hold steady. Don’t drift up." Fuel between efforts. |
| VO2max | Short Z5+ intervals (2–5 min) with recovery | Power compliance. "All out." Recovery quality. "Actually recover." |
| Threshold | Z4 intervals 8–20 min | Ceiling enforcement. "Don’t overcook early." Cadence maintenance. |
| Endurance with surges | Z2 base \+ short Z4-5 spikes | Base discipline between surges. "Back to Z2. Settle." |
| Over-under | Alternating slightly above/below threshold | Precision coaching. "Over phase. Hold 10W above." / "Under. Drop 10W." |
| Recovery / easy | Z1 only, short duration | Strict ceiling. "Too hard for a recovery ride. Ease." |

# **4\. Complete Coaching Matrix**

PacePilot’s coaching adapts across every combination of mode and context. Here’s the full picture:

| Mode | Pacing | Fueling | Climbing | Fatigue |
| :---- | :---- | :---- | :---- | :---- |
| WORKOUT | Interval target compliance. Over/under detection. Rep-by-rep tracking. | Pre-effort fueling prompts. Recovery window fueling. Post-session reminder. | If workout includes outdoor climbs, merge 7Climb pacing with interval targets. | Inter-set recovery quality. HR recovery rate trending. "Consider cutting session." |
| CLIMB-FOCUSED | Consume 7Climb: W′ balance, target power, PUSH/EASE advice. Reinforce with coaching voice. | Pre-climb proactive fueling. Fuel at summits during descents. | Full 7Climb integration. PacePilot adds coaching prompts to 7Climb data fields. | Multi-climb fatigue tracking. "This is climb 3 of 5\. HR baseline is rising." |
| ENDURANCE | Zone discipline. Drift detection. "You’re in tempo." | Time-based \+ NomRide carb deficit. Adaptive intervals based on effort. | Temporary climb coaching when 7Climb detects a climb. Revert on flat. | HR decoupling. Late-ride drift. "Protect the last hour." |
| ADAPTIVE | Observe first, coach later. Build profile from first 10 min. | Standard fueling prompts after 30 min. | React to 7Climb climb detection. | General fatigue monitoring once baseline is established. |

# **5\. TrainingPeaks / Intervals.icu Integration**

## **5.1 What Karoo already does**

Karoo syncs structured workouts from TrainingPeaks (and now intervals.icu). The workout appears on the device with interval targets, graphical overlay, and skip/rewind/scale controls. Karoo creates automatic lap markers at each interval boundary.

## **5.2 What PacePilot adds**

Karoo’s workout view shows you the target and your actual output as numbers and a bar graph. That’s a dashboard. PacePilot turns it into coaching:

* Context-aware prompts: "Effort in 60 sec" not just a bar appearing

* Quality assessment: "Power fading in rep 4\. Consider stopping." not just target vs actual

* Cross-signal intelligence: "Fuel now, hard effort coming" by combining NomRide \+ workout schedule

* Recovery coaching: "HR still elevated. Spin easy." not just a recovery bar

* Session-level wisdom: "Recovery between sets is slowing. Reduce intensity 5%."

* Post-workout transition: "Session done. Switch to easy riding. Fuel within 20 min."

## **5.3 Technical integration approach**

Priority order for workout data access:

1. Investigate karoo-ext RideState for workout state fields. If current interval, target, and time remaining are available via KarooSystemService consumers, this is the integration path. Test on device in week 1\.

2. If not available via SDK: use TrainingPeaks API to fetch today’s workout. Parse workout structure into PacePilot’s internal WorkoutPlan model. Sync once via WiFi before ride.

3. If TP API requires premium: use intervals.icu API (free) as primary, TP as secondary.

4. Fallback: allow manual workout import via FIT/ZWO file placed on device storage.

# **6\. Updated System Architecture**

The addition of workout intelligence adds one new module and modifies the state engine:

## **New module: workout/**

* WorkoutDetector: checks if a structured workout is active on Karoo at ride start

* WorkoutParser: converts workout data (from SDK, TP API, or file) into internal WorkoutPlan model

* IntervalTracker: tracks current position within workout, time remaining, upcoming transitions

* WorkoutCoachingRules: pure functions for all workout-specific coaching decisions

## **Modified: state/RideStateEngine**

RideState now includes:

val workoutState: WorkoutState? // null if no workout active

  // WorkoutState contains:

  //   currentInterval: IntervalSpec (type, target, duration)

  //   intervalElapsedSec: Int

  //   intervalRemainingSec: Int

  //   nextInterval: IntervalSpec?

  //   completedIntervals: Int

  //   totalIntervals: Int

  //   complianceScore: Float // 0-1, how well rider is hitting targets

  //   recoveryQuality: Float // HR recovery rate between efforts

  //   powerFadingTrend: Boolean // true if interval power declining across sets

## **Modified: Smart mode detection**

The mode detection cascade replaces the manual ride mode selector as the default. ModeDetector runs at ride start and emits the initial mode, then monitors for transitions throughout the ride.

# **7\. Updated Coaching Event Schema**

{

  "timestamp": "2026-03-10T15:10:00Z",

  "mode": "workout",

  "mode\_source": "auto\_detected",  // auto\_detected | manual\_override

  "terrain\_context": "flat",

  "ride\_phase": "interval\_3\_of\_6",

  "workout\_context": {

    "interval\_type": "threshold",

    "target\_power\_w": 260,

    "actual\_power\_30s\_avg": 245,

    "interval\_remaining\_sec": 180,

    "recovery\_quality\_trend": "declining"

  },

  "priority": "medium",

  "decision\_type": "workout\_compliance",

  "rule\_id": "power\_below\_target",

  "reasoning": "30s\_avg\_below\_target\_floor\_by\_15w",

  "message": "Push slightly. 15W below target.",

  "fallback\_message": "Increase effort.",

  "sources\_used": \["karoo:workout\_state", "karoo:power"\],

  "cooldown\_sec": 60,  // shorter cooldown during intervals

  "requires\_confirmation": false

}

Note the shorter cooldown during workout mode — intervals need more frequent coaching than endurance riding. Target: 1 prompt per 60–90 seconds during efforts, 1 per 3–5 minutes during recovery.

# **8\. Real-World Scenario: Your 70.3 Training**

Here’s how PacePilot coaches through a typical Ironman 70.3 bike training session from TrainingPeaks:

**Session: 2h30 endurance ride with 3x10min sweet spot intervals**

| Time | What Karoo shows | What PacePilot says |
| :---- | :---- | :---- |
| 0:00 | Workout loaded. Warmup interval active. | "Warmup. Easy spin for 15 min." |
| 0:12 | Power briefly hits Z3 | "Too hard for warmup. Settle." |
| 0:14 | Approaching first effort | "Effort in 60 sec. Get ready." |
| 0:14:30 | NomRide shows 0g eaten | "Take a gel before the effort." |
| 0:15 | SS interval 1 starts. Target: 230-250W | — (let rider settle for 60 sec) |
| 0:16 | Power at 245W, in range | "Good. Hold 245." |
| 0:21 | Power drifting to 260W | "Ease slightly. 10W above ceiling." |
| 0:24:30 | 30 sec left | "30 sec. Hold." |
| 0:25 | Recovery interval starts | "Recover. Drop to Z1." |
| 0:27 | Power still in Z2 | "Actually recover. Easy spinning." |
| 0:28 | NomRide shows \-20g carb deficit | "Good time to fuel." |
| 0:30 | Recovery ending, next effort approaching | "Effort in 60 sec." |
| ... | ... | ... |
| 0:55 | SS interval 3 starts. HR recovery was slower. | "Final block. Recovery was slower — steady start." |
| 1:01 | Power 220W, fading vs intervals 1-2 | "15W below target. Push if you can." |
| 1:05 | All intervals done. Cooldown. | "Session complete. Easy riding home. Fuel within 20 min." |
| 1:30 | Endurance riding. 7Climb detects climb. | "Climb ahead. Easy effort — this is cooldown." |
| 2:00 | NomRide shows \-40g deficit | "Fuel now. Long ride, protect recovery." |
| 2:30 | Ride ending | "Good session. 3/3 intervals completed." |

That’s 15 coaching prompts across 2.5 hours. Each one is contextual, brief, and useful. That’s what a sports director does.

# **9\. Kernel Prompt Additions for Workout Intelligence**

*Add these blocks to the Phase 1 kernel prompt from v3:*

\# WORKOUT INTELLIGENCE

PacePilot must detect and coach through structured workouts.

\#\# Smart Mode Detection (replaces manual mode selection)

Implement a ModeDetector that evaluates at ride start:

1\. Is a structured workout active? \-\> WORKOUT mode

2\. Route loaded with significant climbing? \-\> CLIMB\_FOCUSED mode

3\. Route loaded, flat/rolling? \-\> ENDURANCE mode

4\. Nothing? \-\> ADAPTIVE mode (infer from first 10 min)

Modes transition dynamically during ride:

\- WORKOUT completes all intervals \-\> ENDURANCE for cooldown

\- ENDURANCE enters 7Climb-detected climb \-\> temp CLIMB coaching

\- ADAPTIVE observes sustained Z1 \-\> RECOVERY coaching

\#\# Workout Data Access

Priority 1: Read workout state from KarooSystemService (investigate RideState)

Priority 2: Fetch today's workout from TrainingPeaks or intervals.icu API

Priority 3: Parse FIT/ZWO workout file from device storage

\#\# WorkoutPlan Model

data class WorkoutPlan(

  val source: WorkoutSource, // KAROO\_NATIVE | TRAININGPEAKS | INTERVALS\_ICU | FILE

  val intervals: List\<IntervalSpec\>,

  val totalDurationSec: Int,

  val estimatedTSS: Int?,

)

data class IntervalSpec(

  val type: IntervalType, // WARMUP | EFFORT | RECOVERY | COOLDOWN

  val durationSec: Int,

  val targetType: TargetType, // POWER | HR | RPE

  val targetLow: Int?,  // e.g. 230W or 145bpm

  val targetHigh: Int?, // e.g. 250W or 155bpm

  val cadenceTarget: IntRange?, // optional

)

\#\# Workout Coaching Rules (pure functions)

Write rules for:

\- pre\_interval\_alert (60-90 sec before effort)

\- pre\_interval\_fueling (effort approaching \+ carb deficit)

\- power\_above\_target (\>10% above ceiling for 30s)

\- power\_below\_target (\>10% below floor for 30s)

\- power\_on\_target (positive reinforcement)

\- interval\_countdown (30 sec remaining)

\- recovery\_not\_recovering (Z3+ during recovery)

\- hr\_not\_dropping (HR elevated 60s into recovery)

\- recovery\_fueling\_window (good time to eat during recovery)

\- power\_fading\_trend (declining across sets)

\- recovery\_quality\_declining (HR recovery rate slowing)

\- session\_complete (all intervals done, transition coaching)

\- last\_interval\_motivation (final block encouragement)

\#\# Cooldown Timing

During WORKOUT mode, cooldown between prompts is shorter:

\- During effort intervals: 60 sec cooldown

\- During recovery intervals: 120 sec cooldown

\- During warmup/cooldown: 180 sec cooldown

\- Non-workout modes: 180-300 sec cooldown (unchanged)

# **10\. What Changed from v3**

| Area | v3 | v3.1 (this document) |
| :---- | :---- | :---- |
| Ride modes | Manual selection before ride | Smart auto-detection cascade. Manual as override. |
| Workout support | Deferred to v1.5 | Core v1 feature. Highest-value coaching surface. |
| TrainingPeaks | Not addressed | Direct integration for workout parsing. Karoo SDK preferred, API as fallback. |
| Mode transitions | Static for entire ride | Dynamic: workout → endurance, endurance → climb, adaptive → recovery |
| Interval coaching | None | 13 workout-specific coaching rules across pre/during/recovery/session phases |
| Cooldown timing | Fixed 3-5 min | Context-dependent: 60s during intervals, 120s recovery, 180-300s endurance |
| 70.3 relevance | Generic endurance positioning | Explicit triathlon training scenario with TP workout coaching |

# **11\. Revised MVP Priority**

Workout coaching is now the v1 wedge, not endurance drift detection. Here’s why:

* Riders doing structured training already use their Karoo for workouts via TrainingPeaks — they’re the most engaged users

* The gap between "seeing targets" and "being coached" is the clearest product insight

* Workout coaching generates the densest coaching events per ride — best for validation

* Every coached workout is a retention hook — you don’t want to go back to just seeing bars

* For 70.3 training specifically, this is the difference between a bike computer and a coach

**v1 wedge:** Structured workout coaching \+ adaptive fueling intelligence. If a workout is loaded, PacePilot coaches you through it. If not, it coaches your endurance ride. Either way, it manages your fueling.

## **Revised 3-week build**

**Week 1**

* Karoo extension scaffold

* Investigate workout state availability via karoo-ext SDK (this determines integration path)

* Multi-source telemetry aggregator (Karoo \+ optional NomRide \+ optional 7Climb)

* Smart mode detection: workout vs endurance vs adaptive

* WorkoutPlan parser (from SDK or TP API or file)

**Week 2**

* Workout coaching rules (13 rules from Section 3.2)

* Endurance coaching rules (5 rules from v3)

* Coaching card DataType \+ alert dispatch

* BonusAction for fuel confirmation

* Settings: TP/intervals.icu account, thresholds, alert preferences

**Week 3**

* Founder-led testing: 3 workout rides, 2 endurance rides

* Rule tuning from real ride data

* Battery profiling

* Cooldown and suppression tuning

* Beta to 5 riders who use TP \+ Karoo