# PacePilot Ship PRD v2.1

Status: Execution PRD (source of truth for delivery)
Owner: PacePilot core
Updated: 2026-03-11

## 1) Product Objective

PacePilot is the decision layer for Karoo rides: convert multi-signal ride data into one clear, timely action.

Primary success outcome:
- Improve in-ride execution quality (pacing + fueling + mode adherence) without increasing alert fatigue.

## 2) Strategic Position

- NomRide owns tracking UX and nutrition logging discipline.
- PacePilot owns contextual decisions and adaptive coaching.
- Winning product = NomRide-grade reliability + PacePilot-grade inference.

## 3) Scope Boundaries

### In scope
- In-ride coaching decisions
- Data fields for ambient coaching state
- FIT export for coaching events
- Active-ride resilience and restore
- Smart fueling intelligence and route/climb-aware timing
- Optional external adapters (NomRide, 7Climb, Headwind)
- Post-ride explainable coaching summary

### Out of scope (v2.1 cycle)
- Phone companion app
- Cloud dashboard
- Cross-device account sync
- Marketplace billing implementation
- Full social/sharing layer

## 4) Phased Delivery Plan

## Phase 0 — Hardening Baseline (Week 0-1)

Deliverables:
- Alert text policy and truncation safeguards
- Lifecycle and process-restart hardening
- Instrumentation hooks (alerts fired, suppressed, upgraded)

Acceptance criteria:
- No crash in 3x 90-minute rides.
- Alert messages never visually overflow known Karoo constraints.
- Logs include counters for fired/suppressed/upgraded alerts.

Non-goals:
- No new external integrations.
- No redesign of coaching logic.

## Phase 1 — Coaching Surface + Trust Layer (Week 1-4) [P0]

Deliverables:
- 3 PacePilot data fields: coaching_status, zone_time_sec, ride_score
- Snooze + undo snooze actions
- FIT developer-field export for coaching events
- Active-ride state snapshot and restore

Acceptance criteria:
- Data fields selectable and updating during ride.
- Undo Snooze re-enables alerts immediately.
- FIT file contains developer fields for coaching events.
- Service restart during ride recovers state in <= 2s.

Non-goals:
- No weather/wind-aware pacing yet.
- No post-ride AI summary yet.

## Phase 2 — Smart Fueling (Week 5-8) [P1]

Deliverables:
- Intensity-aware carbs/hour target engine
- Absorption-window timing cues
- Terrain-aware fueling prompts
- NomRide adapter + internal fallback estimator

Acceptance criteria:
- Carb target updates by effort level (zone-dependent).
- Pre-effort fueling prompts trigger when deficit and timing conditions are met.
- NomRide presence toggles data source without crashes.

Non-goals:
- No full nutrition planner UI.
- No third-party cloud nutrition sync.

## Phase 3 — Climb + Route Strategy (Week 9-12) [P1/P2]

Deliverables:
- Upcoming climb preparation alerts
- Multi-climb effort budgeting signal
- Summit proximity pacing cues
- Optional 7Climb/Headwind adapters with fallback

Acceptance criteria:
- Pre-climb cue fires before major climbs on routed rides.
- Budget guidance adjusts based on remaining climbing.
- Missing external adapters does not degrade core coaching stability.

Non-goals:
- No race calendar/event planning.
- No map rendering overlays.

## Phase 4 — Intelligence + Retention (Week 13-16) [P2]

Deliverables:
- Post-ride explainable summary
- Event timeline and pattern insights
- Finish-line strategy mode (negative split helper)
- Release cadence and changelog discipline

Acceptance criteria:
- Post-ride summary generated without network dependency on core output.
- Pattern insights appear after sufficient ride history.
- Tagged release artifacts and changelog published per release.

Non-goals:
- No coach web dashboard.
- No subscription paywall implementation.

## 5) Product Requirements by Pillar

### 5.1 Coaching Surface
- Real-time data fields with low-overhead stream updates.
- In-ride actions: snooze, undo_snooze, log_fuel, log_drink.
- Alert policy constraints:
  - global minimum alert gap
  - max alerts/hour cap
  - critical alert bypass

### 5.2 Smart Fueling
- Dynamic target carbs/hour from current effort profile.
- Deficit and timing model with confidence score.
- Prompt prioritization:
  1) safety/overexertion
  2) imminent effort fueling
  3) routine reminders

### 5.3 Climb and Route Intelligence
- Route-aware pre-climb prep
- Remaining-climb-aware pacing budget
- End-game pacing cueing

### 5.4 Explainability and Trust
- FIT event export (developer fields)
- Human-readable post-ride summary
- Traceable event timeline for review

## 6) Interfaces and Contracts

### 6.1 NomRide adapter contract (optional)
- Inputs:
  - carb_balance_g
  - burn_rate_gph
  - carbs_eaten_g
  - water_ml
- Fallback:
  - internal estimator with explicit source flag

### 6.2 7Climb adapter contract (optional)
- Inputs:
  - distance_to_top_m
  - current_gradient_pct
  - climb_index / total_climbs
- Fallback:
  - native grade and route-derived heuristic

### 6.3 Headwind adapter contract (optional)
- Inputs:
  - relative_wind_component
  - wind_speed
- Fallback:
  - no wind adjustment

## 7) SLAs

- Alert decision latency: <= 100 ms from tick evaluation
- AI upgrade timeout budget: <= 3 s soft cap
- Alert interruption budget: <= 15/hour default
- Service restart restore: <= 2 s
- Crash-free rides: 100% target

## 8) Telemetry and KPIs

- Alerts fired per hour
- Alerts suppressed by policy
- AI upgrade success rate
- Snooze rate and undo rate
- Coaching compliance trend (10+ rides)
- Fuel deficit violation frequency

## 9) Release Model

- Weekly internal build
- Bi-weekly tagged beta
- Stable tag when:
  - compile + test pass
  - ride smoke tests pass
  - crash-free acceptance checks pass

## 10) Traceability Matrix (Stories -> Components)

- Data fields -> extension_info + DataTypeImpl registry
- Snooze/undo -> BonusAction handlers + ride context silence state
- FIT export -> startFit + event emitter bridge
- State restore -> active ride snapshot store + extension lifecycle
- Fueling intelligence -> telemetry target model + coaching rules
- Route/climb strategy -> navigation/climb streams + climb rule pack
- Post-ride intelligence -> history + summary engine
