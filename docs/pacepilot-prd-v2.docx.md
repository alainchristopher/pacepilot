  
PRODUCT REQUIREMENTS DOCUMENT

**PacePilot v2.0**

The Decision Layer for Cycling

Vision • Roadmap • User Stories • Technical Requirements

ACB Consulting | March 2026

**CONFIDENTIAL**

# **1\. Product Vision**

## **1.1 The Insight**

The Karoo extension ecosystem is maturing rapidly on the “show me data” front — headwind, nutrition counters, power bars, climb profiles, data fields. But there is essentially zero competition in the “tell me what to do” space. Every existing extension is an instrument. None is a coach.

Cyclists are drowning in data and starving for decisions. They have power meters, HR monitors, cadence sensors, GPS, gradient data, wind data, nutrition trackers — and they’re expected to synthesize all of it, in real time, while riding, into correct pacing, fueling, and effort decisions. This is an unreasonable ask, especially for the growing population of enthusiast cyclists who lack coaching experience.

## **1.2 The Vision**

**PacePilot is the decision layer for cycling.** It sits on top of the Karoo’s data ecosystem — consuming telemetry, nutrition state, climb profiles, route data, and ride history — and translates all of it into a single, clear coaching instruction delivered at the right moment. It does not compete with data-display extensions. It makes them smarter by being the brain that interprets their output.

## **1.3 Positioning Statement**

*For* **Karoo-riding cyclists who train with power** *who are* **frustrated by the gap between pre-ride planning and in-ride execution***, PacePilot is* **a real-time coaching extension** *that* **watches your data and tells you what to do next***. Unlike nutrition trackers and data fields, PacePilot* **interprets context across power, nutrition, terrain, fatigue, and history to deliver a single coaching action.**

## **1.4 Design Principles**

* **Coach, don’t display.** Every output is an action the rider can take, not a number to interpret.

* **Consume, don’t rebuild.** Use NomRide for nutrition data, 7Climb for climb data, route data from Karoo. Don’t rebuild what others do well.

* **Offline-first, AI-enhanced.** The rider always gets coached. AI makes it personal when connected.

* **Respect attention.** Every alert must earn its interruption. Alert fatigue is product death.

* **Persistent presence, minimal intrusion.** Data fields for ambient awareness, alerts for action moments.

* **Leave a trace.** Every coaching event is recorded in the FIT file and available for post-ride review.

# **2\. Competitive Landscape Synthesis**

Analysis of the full Karoo extension ecosystem reveals three categories and one gap:

| Category | Extensions | What They Do | What’s Missing |
| :---- | :---- | :---- | :---- |
| Data Display | VinHkE, KDouble, karoo-powerbar, karoo-colorspeed | Show data in new formats: coloured fields, double metrics, progress bars | No interpretation. Rider must still decide. |
| Nutrition Tracking | NomRide, karoo-reminder | Track carb balance, burn rate, hydration. Timer-based eat/drink reminders. | No intensity-aware timing. No integration with effort or terrain. |
| Climb Intelligence | 7Climb, karoo-routegraph | Show climb profiles, remaining distance, gradient ahead. | No pacing strategy. No multi-climb effort budgeting. |
| Weather/Environment | Headwind, Epic Ride Weather, myWindsock | Wind direction, speed, weather forecasts. | No integration with pacing. Wind data isn’t used for effort adjustment. |
| COACHING (GAP) | None | — | Nobody synthesizes data into “what should I do right now?” PacePilot fills this. |

## **2.1 Key Learnings from NomRide**

* **Data fields matter.** NomRide’s 6 data fields with 6 layout sizes give it persistent screen presence. PacePilot needs custom data fields.

* **In-ride actions matter.** NomRide’s Quick Gel one-tap log and Undo Last let the rider interact. PacePilot needs bidirectional communication.

* **State persistence matters.** NomRide saves state every 30 seconds. PacePilot must survive process death.

* **FIT file export matters.** NomRide writes nutrition data to the FIT file. PacePilot coaching events must be recorded.

* **Release discipline matters.** NomRide shipped v1.0.0 and iterated to v1.2.2 with clear changelogs. PacePilot has zero releases.

* **Landing page matters.** NomRide’s site is beautiful and explains the science. PacePilot needs equivalent presence.

# **3\. Target Users**

## **3.1 Primary: The Self-Coached Enthusiast**

Rides 3–5x/week. Owns a power meter. Uses TrainingPeaks or Intervals.icu for planning. Does NOT have a human coach. Makes pacing mistakes regularly — goes too hard on climbs, forgets to eat, drifts out of zone. Wants to improve but can’t afford €200/month coaching.

## **3.2 Secondary: The Triathlete**

Training for 70.3 or Ironman. The bike leg is where races are lost. Pacing and nutrition are existential concerns. Needs strict zone ceiling enforcement because they still have to run. Values fueling precision over everything.

## **3.3 Tertiary: The Coached Athlete**

Has a human coach who sets training plans. PacePilot enforces the plan in real time and reports back. The coach can review PacePilot’s FIT file coaching events to see how the athlete executed. Future opportunity for a coach-facing dashboard.

# **4\. Feature Pillars**

## **4.1 Pillar 1: Smart Coaching Engine (exists, evolve)**

The core rules engine with 25+ coaching rules across 5 ride modes. This is PacePilot’s foundation. Enhancements:

* Add data fields for persistent screen presence (coaching status, zone time, fatigue indicator)

* Add Bonus Actions (snooze coaching, log fuel, switch ride mode)

* Write coaching events to FIT file as DeveloperFields

* Implement state persistence (survive process death, save every 30s)

* Add configurable rule enable/disable per rule

## **4.2 Pillar 2: Smart Fueling System (new)**

Move beyond timer-based fueling reminders to a glycogen depletion model that consumes NomRide’s carb balance data when available and falls back to PacePilot’s own power-based estimation when not.

* **Glycogen depletion modeling:** Track estimated glycogen stores based on intensity, duration, and intake. Alert before depletion, not after.

* **NomRide integration:** Read NomRide’s carb\_balance, burn\_rate, and carbs\_eaten fields via Karoo’s data field API. Use precise data when available.

* **Terrain-aware timing:** If a climb is coming in 5 minutes, prompt fueling now because absorption takes 15–20 minutes.

* **Intensity-aware recommendations:** Recommend different carb targets based on current zone: 30g/h in Z2, 60–90g/h at tempo and above.

* **Hydration coupling:** Remind to drink when eating, because dehydration impairs gut absorption.

## **4.3 Pillar 3: Smart Climb Mode (new)**

Transform from reactive climb coaching (detect grade \> 3%, apply rules) to proactive route-aware climb strategy:

* **Route-aware climb detection:** If a route is loaded, pre-identify all climbs from the elevation profile. Know what’s coming before it arrives.

* **Multi-climb effort budgeting:** If there are 3 climbs remaining, distribute effort based on length, gradient, and position in the ride. Don’t blow up on climb 1 of 3\.

* **Pre-climb preparation:** “Climb in 2 km — eat now, drop to Z2, spin up cadence” alerts 2–5 minutes before the gradient kicks in.

* **Summit proximity pacing:** As summit approaches, adjust effort ceiling: hold back early, release in the final 500m if the rider has headroom.

* **7Climb integration:** Consume 7Climb’s climb data (remaining distance, gradient, elevation) rather than rebuilding climb detection.

## **4.4 Pillar 4: Route-Aware Pacing (new)**

Use route data (when available) to provide forward-looking coaching:

* **Finish line awareness:** “3 km to finish — you have room to push” or “15 km remaining, hold Z2 for the next 10 then empty the tank.”

* **Wind-adjusted effort:** If Headwind extension data is available, adjust target power: push harder into headwind sections, recover on tailwind sections to even out effort.

* **Segment awareness:** If approaching a Strava segment or POI, alert the rider to prepare.

* **Negative split coaching:** For target-time rides, actively manage effort to deliver a negative split (second half faster than first).

## **4.5 Pillar 5: Post-Ride Intelligence (new)**

Create a second engagement touchpoint after the ride ends:

* **AI ride summary:** Generate a 3–5 sentence coaching summary: what went well, what to improve, how this ride compares to recent history.

* **Coaching event log:** Export all coaching events from the FIT file as a reviewable list. The rider (or their coach) can see every alert that fired and why.

* **Pattern detection:** Over 30 rides, surface patterns: “You consistently push too hard in the first 20 minutes” or “Your HR:power decoupling starts earlier when you skip fueling.”

* **TrainingPeaks/Intervals.icu sync:** Push coaching annotations to training platforms so they appear alongside power data.

# **5\. User Stories**

## **5.1 Core Coaching (P0 — Ship First)**

**US-01:** *As a self-coached cyclist, I want* **to see my current coaching status on the ride screen at all times** *so that* I know PacePilot is active and what mode it’s in without waiting for an alert.

**Acceptance:** Data field shows: ride mode, time since last alert, coaching status (active/snoozed). 6 layout sizes.

**Priority: P0**

**US-02:** *As a rider mid-effort, I want* **to snooze coaching alerts for a configurable period** *so that* I’m not distracted during a hard effort or group ride when I don’t need coaching.

**Acceptance:** Bonus Action “Snooze 15 min” pauses all non-critical alerts. Critical alerts (safety) still fire. Snooze timer visible in data field.

**Priority: P0**

**US-03:** *As a rider who reviews rides, I want* **coaching events written to my FIT file** *so that* I can see what PacePilot told me when I review the ride in Intervals.icu.

**Acceptance:** DeveloperFields: coaching\_event\_id, coaching\_message, coaching\_rule\_id written at event time. Session summary includes total alerts fired.

**Priority: P0**

**US-04:** *As a rider whose Karoo restarts mid-ride, I want* **PacePilot to resume coaching without losing context** *so that* I don’t miss coaching because the extension restarted.

**Acceptance:** State saved every 30s: ride context, coaching cooldowns, ride narrative, mode. Restores within 2 seconds of service restart.

**Priority: P0**

**US-05:** *As a cyclist who uses multiple extensions, I want* **PacePilot to feel native to the Karoo** *so that* it doesn’t look bolted on or clash with other UI.

**Acceptance:** InRideAlerts use Karoo’s native system. Data fields match Karoo’s Glance system with proper layout sizes. Settings accessible via CONFIGURE\_EXTENSION intent.

**Priority: P0**

## **5.2 Smart Fueling (P1 — High Impact)**

**US-06:** *As a long-distance rider, I want* **intensity-aware fueling recommendations** *so that* I eat the right amount for how hard I’m working, not a fixed amount per hour.

**Acceptance:** Recommendations scale: 30g/h in Z1–Z2, 45g/h in Z3, 60–90g/h in Z4+. Adjusts dynamically as intensity changes.

**Priority: P1**

**US-07:** *As a rider with NomRide installed, I want* **PacePilot to use NomRide’s precise carb data** *so that* coaching is based on actual intake data, not estimates.

**Acceptance:** PacePilot detects NomRide via data field availability. Reads carb\_balance and burn\_rate. Falls back to internal estimation when NomRide is absent.

**Priority: P1**

**US-08:** *As a rider approaching a climb, I want* **a fueling reminder before the climb starts** *so that* I have fuel in my system before the hard effort begins.

**Acceptance:** If route data shows a climb in 2–5 km AND carb balance is negative or time since last fuel \> 30 min, fire pre-climb fueling alert.

**Priority: P1**

**US-09:** *As a rider who just ate, I want* **to tell PacePilot I’ve eaten** *so that* it stops reminding me to eat and resets the fuel timer.

**Acceptance:** Bonus Action “Logged Fuel” resets the fueling timer and records intake. If NomRide is installed, PacePilot defers to NomRide’s data instead.

**Priority: P1**

## **5.3 Smart Climb Mode (P1 — High Impact)**

**US-10:** *As a rider with a route loaded, I want* **PacePilot to know about upcoming climbs before I reach them** *so that* I get preparation alerts (fuel, gear, cadence) before the gradient hits.

**Acceptance:** Parse route elevation profile on ride start. Identify climbs (sustained grade \> 3% for \> 500m). Alert 2–5 min before each climb with prep instructions.

**Priority: P1**

**US-11:** *As a rider on a multi-climb route, I want* **effort budgeting across all remaining climbs** *so that* I don’t empty myself on climb 1 of 3\.

**Acceptance:** Calculate total climbing remaining. Distribute power budget based on climb length and gradient. Alert if current effort exceeds budget for this climb.

**Priority: P1**

**US-12:** *As a rider mid-climb, I want* **summit proximity pacing** *so that* I know when to hold back and when to push.

**Acceptance:** Show distance to summit. If rider has headroom (HR below threshold, power sustainable), suggest pushing in final 500m. If fading, suggest survival pacing.

**Priority: P1**

## **5.4 Route-Aware Pacing (P2 — Differentiator)**

**US-13:** *As a rider on a loaded route, I want* **finish-line awareness coaching** *so that* I can pace the final kilometres correctly.

**Acceptance:** When remaining distance \< 5 km, shift coaching to end-game mode: “Room to push” or “Hold steady” based on fatigue state.

**Priority: P2**

**US-14:** *As a rider with Headwind extension, I want* **wind-adjusted effort targets** *so that* I push harder into headwinds and recover on tailwinds for even effort distribution.

**Acceptance:** Read Headwind extension’s wind data. Adjust coaching targets: \+5–10% power into headwind, \-5–10% with tailwind.

**Priority: P2**

**US-15:** *As a rider targeting a race time, I want* **negative split pacing** *so that* my second half is faster than my first and I finish strong.

**Acceptance:** Accept target time/power in settings. Actively manage effort to deliver progressive build: conservative first half, stronger second half.

**Priority: P2**

## **5.5 Post-Ride Intelligence (P2 — Retention)**

**US-16:** *As a rider who just finished, I want* **an AI-generated ride summary** *so that* I understand what I did well and what to improve without reading raw data.

**Acceptance:** Generate 3–5 sentence summary via Mercury-2/Gemini. Compare to recent rides. Highlight key coaching events. Display on Karoo after ride save.

**Priority: P2**

**US-17:** *As a rider tracking progress over time, I want* **pattern detection across my ride history** *so that* I can see trends in my mistakes and improvements.

**Acceptance:** After 10+ rides, surface patterns: consistent zone drift timing, fueling gaps, climb pacing trends. Show in settings screen or post-ride card.

**Priority: P2**

**US-18:** *As a coached athlete, I want* **to share coaching logs with my coach** *so that* my coach can see how I executed the plan during the ride.

**Acceptance:** Export coaching event log from FIT file. Future: coach-facing dashboard showing athlete’s coaching events over time.

**Priority: P2**

# **6\. Roadmap**

Phased delivery across 4 milestones over 12 weeks:

| Phase | Timeline | Deliverables | Success Criteria |
| :---- | :---- | :---- | :---- |
| v1.0 — Foundation | Weeks 1–3 | Tagged GitHub release with APK. 3 custom data fields (coaching status, zone time, fatigue). FIT file coaching events. State persistence. 1 Bonus Action (snooze). Landing page. Hammerhead Extension Library submission. | Downloadable APK. 5+ beta testers. Extension Library application submitted. |
| v1.5 — Smart Fueling | Weeks 4–6 | Glycogen depletion model. NomRide data integration. Terrain-aware fuel timing. Intensity-scaled carb targets. Log Fuel bonus action. Hydration coupling. | Fueling alerts fire based on intensity \+ deficit, not just time. NomRide users see integrated coaching. |
| v2.0 — Smart Climb | Weeks 7–9 | Route-aware climb detection. Multi-climb effort budgeting. Pre-climb preparation alerts. Summit proximity pacing. 7Climb data integration. | Pre-climb alerts fire 2–5 min early on routes with climbs. Multi-climb rides show effort budget. |
| v2.5 — Intelligence | Weeks 10–12 | Post-ride AI summary. Pattern detection (10+ rides). Finish-line awareness. Wind-adjusted pacing (Headwind integration). Negative split mode. | Post-ride summary generates on ride save. 30-ride patterns surfaced. Wind pacing active when Headwind is installed. |

# **7\. Technical Requirements**

## **7.1 Data Fields**

PacePilot must expose custom data fields via Karoo’s Glance system:

| Field | Type | Layouts | Content |
| :---- | :---- | :---- | :---- |
| Coaching Status | Graphical | All 6 sizes | Current ride mode icon \+ name. Time since last alert. Snoozed indicator. Colour: green (active), orange (snoozed), dim (no rules fired recently). |
| Zone Time | Graphical | All 6 sizes | Time in current zone. Zone name and colour. Target zone indicator if in workout mode. |
| Ride Score | Numeric | All 6 sizes | Rolling coaching compliance score: % of time spent in target zones, fueling on schedule, pacing within budget. 0–100. |

## **7.2 Bonus Actions**

| Action | Behaviour | Priority |
| :---- | :---- | :---- |
| Snooze Coaching | Suppress non-critical alerts for 15 min (configurable). Show countdown in data field. Critical alerts (safety, severe fatigue) still fire. | P0 |
| Log Fuel | Record fuel intake timestamp. Reset fueling timer. If NomRide present, defer to NomRide. | P1 |
| Switch Mode | Force ride mode to Endurance/Recovery/Workout. Override auto-detection. | P2 |
| Undo Last Snooze | Cancel active snooze. Resume normal coaching. | P2 |

## **7.3 FIT File Integration**

Write coaching events as DeveloperFields in the FIT file:

* Field: coaching\_event (string) — the coaching message displayed to the rider

* Field: coaching\_rule\_id (uint8) — the rule that fired

* Field: coaching\_mode (uint8) — active ride mode at time of event

* Recording: event-driven (write on each coaching event, not continuous)

* Session summary: total alerts fired, alerts per category, coaching compliance score

## **7.4 Extension Data Consumption**

PacePilot should consume data from other extensions when available:

| Extension | Data Consumed | Fallback When Absent |
| :---- | :---- | :---- |
| NomRide | carb\_balance (g), burn\_rate (g/h), carbs\_eaten (g), water\_ml (ml) | Internal power-based carb estimation. Timer-based reminders. |
| 7Climb | Climb distance remaining, gradient, elevation gain | Detect climbs from GPS gradient only (reactive, not proactive). |
| Headwind | Wind direction, wind speed relative to rider | Ignore wind. Coach on power/HR only. |
| Karoo Route | Route elevation profile, remaining distance, POIs | No route awareness. Reactive coaching only. |

## **7.5 State Persistence**

* Save full coaching state every 30 seconds to SharedPreferences or local file

* State includes: RideContext snapshot, all cooldown timers, ride narrative events, current mode, coaching compliance score, snooze state

* On service restart: detect active ride via Karoo SDK, restore state, resume coaching within 2 seconds

* On ride end: save session to 30-ride rolling history, clear current state

## **7.6 AI Provider Abstraction**

Support multiple AI providers behind a common interface:

* Interface: AiCoachingProvider with generateMessage(context: CoachingContext): String

* Implementations: MercuryProvider (Mercury-2 via Inception API), GeminiProvider (Gemini 2.0 Flash), OfflineProvider (rule message passthrough)

* Provider selection in settings UI with API key management per provider

* Automatic fallback: if selected provider fails (timeout 3s, error, no connectivity), fall through to next provider, then to offline

* Cost tracking: log token usage per ride, display in settings as monthly/per-ride average

## **7.7 UI Requirements**

Settings UI must match the quality standard set by NomRide and Karoo’s native apps:

* Dark theme with warm amber accent (\#E8842C)

* Grouped card layout with section headers (10px uppercase tracking)

* Descriptions below every setting label

* Minimum 44px touch targets for Karoo’s touchscreen

* Consistent Glance composables with centralized colour system

* Proper layout size variants (SMALL through LARGE)

* CONFIGURE\_EXTENSION intent support for settings access from extension list

# **8\. Success Metrics**

## **8.1 Adoption**

* v1.0: 50+ downloads in first 30 days (sideloading \+ Extension Library)

* v1.5: 200+ active riders

* v2.0: Accepted into Hammerhead Extension Library

* v2.5: 500+ active riders, 5+ GitHub stars

## **8.2 Engagement**

* Average coaching alerts per ride: 5–15 (too few \= not useful, too many \= alert fatigue)

* Snooze rate: \< 20% of alerts snoozed (high snooze \= low relevance)

* AI upgrade rate: \> 60% of alerts upgraded from rule to AI when connected

* Session retention: \> 80% of riders who install use PacePilot for 5+ rides

## **8.3 Quality**

* Coaching compliance score improvement: riders who use PacePilot for 30+ rides show measurable improvement in zone adherence and fueling consistency

* Post-ride summary accuracy: \> 80% of riders find the AI summary accurate and useful (survey)

* Zero crashes during ride: crash rate must be 0% for the coaching engine core

## **8.4 Revenue (post-v2.0)**

* Free tier: rules engine \+ basic data fields

* Pro tier (€4.99/mo): AI coaching, ride history, post-ride summaries, pattern detection

* Target: 1,000 Pro subscribers \= €5,000/mo MRR

# **9\. Risks & Mitigations**

| Risk | Likelihood | Impact | Mitigation |
| :---- | :---- | :---- | :---- |
| Karoo disables WiFi during rides, breaking AI layer | High | Medium | Hybrid architecture already handles this. AI is enhancement, not dependency. Mercury-2’s speed helps: even brief connectivity windows are enough. |
| NomRide / 7Climb APIs change or break | Medium | Medium | Graceful fallback: if extension data unavailable, revert to internal estimation. No hard dependencies. |
| Alert fatigue causes riders to uninstall | Medium | High | CooldownManager with per-rule suppression. Configurable frequency. Snooze action. Monitor snooze rate as quality signal. |
| AI provider pricing changes | Medium | Low | Provider abstraction supports multiple backends. Mercury-2, Gemini, and offline all work. Cost tracking in settings. |
| Hammerhead Extension Library rejection | Low | High | Build community traction via sideloading and awesome-karoo first. Submit with demo video, beta tester testimonials, and clean APK. |
| Name conflict with existing PacePilot app | High | Medium | Consider rename to WattSense or RideIQ before public launch. Or proceed with PacePilot if trademark search is clean in cycling-specific classes. |

# **10\. Appendix: Integration Architecture**

PacePilot’s position in the Karoo ecosystem:

┌────────────────────────────────────────────────┐│  DATA SOURCES (existing extensions)       ││  NomRide │ 7Climb │ Headwind │ Karoo SDK   │└────────────────────────────────────────────────┘                        │                        ▼┌────────────────────────────────────────────────┐│  PACEPILOT DECISION LAYER                  ││  Rules Engine │ AI Layer │ Ride History  │└────────────────────────────────────────────────┘                        │              ┌─────────┼─────────┐              ▼                   ▼┌────────────────────┐  ┌────────────────────┐│ InRideAlerts       │  │ Data Fields        ││ (coaching actions)  │  │ (ambient status)   │└────────────────────┘  └────────────────────┘              │                   │              └─────────┼─────────┘                        ▼┌────────────────────────────────────────────────┐│  FIT FILE                                   ││  Coaching events as DeveloperFields          │└────────────────────────────────────────────────┘