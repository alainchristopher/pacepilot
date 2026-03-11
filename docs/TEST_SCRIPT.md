# PacePilot v3.1 — On-Device Test Script

Run these in order. Each section has a pass criterion. Check off as you go.

---

## 0. Pre-flight Setup

1. **USB** → connect Karoo to Mac, confirm ADB: `adb devices` (should show device)
2. Open the **PacePilot** app on Karoo (Extensions menu or app drawer)
3. Enter your **Gemini API key** → save
4. Confirm "Settings saved" toast appears

---

## 1. App Toggle (Master On/Off)

**Goal:** Verify the extension is fully dormant when disabled.

| Step | Action | Expected |
|------|--------|----------|
| 1.1 | Toggle **PacePilot Active** OFF → Save | Switch turns off, all cards grey out |
| 1.2 | Start a free ride on Karoo | No coaching alerts appear, no mode notification |
| 1.3 | Stop ride | No summary logged |
| 1.4 | Open app → Toggle **PacePilot Active** ON → Save | Cards become full opacity |

✅ Pass: No activity at all during step 1.2 with toggle off.

---

## 2. Settings UI

**Goal:** Verify all inputs save and reload correctly.

| Step | Action | Expected |
|------|--------|----------|
| 2.1 | Set FTP = 280 → Save → Reopen app | FTP shows 280 |
| 2.2 | Set Max HR = 185 → Save → Reopen | Max HR shows 185 |
| 2.3 | Set provider to **Mercury** → enter a key → Save → Reopen | Mercury chip selected, key visible |
| 2.4 | Set provider back to **Gemini** → Save → Reopen | Gemini chip selected |
| 2.5 | Set provider to **Off** → Save → Reopen | Off chip selected |
| 2.6 | Set provider back to **Gemini** | Ready for ride tests |

✅ Pass: All values persist across app restarts.

---

## 3. Deep Link (API Key URL)

**Goal:** Verify `pacepilot://` scheme sets keys without opening UI.

Run from Mac terminal:
```bash
adb shell am start -a android.intent.action.VIEW \
  -d "pacepilot://config?gemini_key=YOUR_REAL_KEY&provider=gemini" \
  io.hammerhead.pacepilot
```

| Step | Action | Expected |
|------|--------|----------|
| 3.1 | Run command above with your actual Gemini key | Toast: "Gemini API key saved!" |
| 3.2 | Open PacePilot settings | Gemini chip selected, key partially visible |

✅ Pass: Key saved without manual typing.

---

## 4. Mode Detection

**Goal:** Verify the correct coaching mode fires at ride start.

| Scenario | How to test | Expected notification |
|----------|-------------|----------------------|
| 4.1 Workout | Load a structured workout → start ride | "Workout detected. Coaching active." |
| 4.2 Free ride | Start ride with no workout loaded | "Endurance mode. Pacing coaching active." or similar |
| 4.3 Recovery | Set Forced Mode = **Recover** in settings → start ride | "Recovery ride. Keep it easy." |

✅ Pass: Mode notification appears within ~5 seconds of ride start.

---

## 5. Workout Coaching (Core Test)

**Goal:** Verify interval coaching fires at the right moments.

**Load:** Base Builder (Heart Rate) 1h — 10min warmup / 40min active / 10min cooldown — or any structured workout.

| Step | What to watch for | Expected alert |
|------|------------------|----------------|
| 5.1 | 30s into first interval | "Interval starting — settle into your power" or similar |
| 5.2 | Push 15%+ above target for 30s | "You're overcooking — ease back [X]W" (type-specific message) |
| 5.3 | Drop 15%+ below target for 30s | "Power fading — lift to [X]W" |
| 5.4 | Sit on-target for 60s | Positive reinforcement cue ("Dialled in…") |
| 5.5 | Final interval, last 90s | "Last one — hold it to the end!" or motivational cue |
| 5.6 | After last interval ends | Session complete message |

✅ Pass: At least 4/6 alerts fire at appropriate moments.

**AI upgrade test:** After any alert fires, watch the text. It should update from the static message to a richer AI-generated version within 2–4 seconds (Gemini) or 1–2s (Mercury).

---

## 6. Fueling Reminders

**Goal:** Verify carb + drink reminders trigger.

| Step | Action | Expected |
|------|--------|----------|
| 6.1 | Ride for 40 minutes without eating | Fueling reminder fires (~30–40 min mark) |
| 6.2 | Ride for 20 minutes without drinking | Drink reminder fires (~20 min mark) |
| 6.3 | Dismiss the alert (Karoo swipe/dismiss) | No immediate repeat |

✅ Pass: Both reminders fire once per window.

---

## 7. Mode Transitions (Dynamic)

**Goal:** Verify mode changes mid-ride.

| Scenario | How | Expected |
|----------|-----|---------|
| 7.1 Climb detection | Find a climb >4% grade and hold it for ~2 min | "Climbing. Maintain threshold — power is your friend." notification |
| 7.2 Recovery detection | After hard effort, drop to Z1 and hold 10+ min | May not visibly notify but coaching tone should soften |

✅ Pass: Climb notification fires during a sustained ascent.

---

## 8. Ride History & AI Context

**Goal:** Verify ride history persists and improves AI cues over multiple rides.

| Step | Action | Expected |
|------|--------|----------|
| 8.1 | Complete and stop a full ride (>5 min) | No crash on stop |
| 8.2 | Start a second ride | AI prompt now includes "past X rides" context |
| 8.3 | Check Gemini response quality | Second ride cues should reference historical patterns |

✅ Pass: Two rides complete without crashes.

---

## 9. Mercury-2 Provider Test (Optional)

**Goal:** Verify Mercury-2 works and is faster than Gemini.

| Step | Action | Expected |
|------|--------|----------|
| 9.1 | Get a free key at [platform.inceptionlabs.ai](https://platform.inceptionlabs.ai) | API key in hand |
| 9.2 | Set provider = Mercury, enter key → Save | Mercury chip selected |
| 9.3 | Start a ride, trigger a coaching event | Alert fires, AI upgrade appears |
| 9.4 | Subjectively compare response time vs Gemini | Mercury should feel snappier (<200ms) |
| 9.5 | Switch back to Gemini after test | Preferred provider restored |

✅ Pass: Alert fires and text upgrades with Mercury key.

---

## 10. Offline / No-AI Fallback

**Goal:** Verify rules-only mode works without internet.

| Step | Action | Expected |
|------|--------|----------|
| 10.1 | Turn phone hotspot OFF (no internet) | — |
| 10.2 | Start a workout ride | Mode notification fires (rules, no AI needed) |
| 10.3 | Trigger an interval alert | Static rule-based message appears instantly |
| 10.4 | AI upgrade text does NOT appear (no internet) | Only the static message visible — no crash |

✅ Pass: Coaching continues working with no internet.

---

## 11. Edge Cases

| Test | Steps | Expected |
|------|-------|---------|
| 11.1 FTP = 0 | Clear FTP in settings → start workout | No power-target alerts fire (null guard active) |
| 11.2 App off mid-ride | Toggle app off in settings during a ride | Has no effect on current ride (toggle only applies on ride start) |
| 11.3 Settings save w/ empty key | Clear Gemini key → set provider = Gemini → save → ride | Rides in rules-only mode silently (no crash) |

---

## ADB Log Monitoring

Open a terminal and watch live logs during any test:
```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb logcat -s PacePilot:D CoachingEngine:D GeminiClient:D MercuryClient:D
```

Key log lines to look for:
- `PacePilot: ride started` — ride lifecycle working
- `CoachingEngine: AI provider GEMINI ready` — Gemini cache created
- `CoachingEngine: AI upgraded "..." → "..."` — AI upgrade working
- `GeminiClient: initRide cache=cachedContents/...` — context cache hit
- `MercuryClient: initRide — context stored in memory` — Mercury ready
- `PacePilot: app disabled — skipping ride start` — app toggle working

---

## Known Limitations (Not Bugs)

- AI cues require phone hotspot or WiFi — Karoo has no cellular
- Mercury-2 is experimental — occasionally returns empty responses (falls back to static)
- Mode detection takes ~8s after ride start (telemetry warm-up delay)
- Workout type classification requires a structured workout from Karoo's workout library
