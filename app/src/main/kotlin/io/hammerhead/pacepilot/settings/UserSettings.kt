package io.hammerhead.pacepilot.settings

import io.hammerhead.pacepilot.ai.LlmProvider
import io.hammerhead.pacepilot.model.RideMode

data class UserSettings(
    /** Master on/off. When false the extension is fully dormant during rides. */
    val appEnabled: Boolean = true,

    /** Override FTP from Karoo profile (0 = use Karoo) */
    val ftpOverride: Int = 0,
    /** Override max HR from Karoo profile (0 = use Karoo) */
    val maxHrOverride: Int = 0,

    /** Master switch for all coaching alerts */
    val alertsEnabled: Boolean = true,
    /** Enable fueling-specific prompts */
    val fuelingAlertsEnabled: Boolean = true,

    /**
     * Cooldown multiplier applied to all timings.
     * 1.0 = spec defaults, 2.0 = half as many alerts, 0.5 = twice as many.
     */
    val cooldownMultiplier: Float = 1.0f,
    /** Hard floor between alerts (non-critical), in seconds */
    val minAlertGapSec: Int = 45,
    /** Max non-critical alerts in rolling 1 hour window */
    val maxAlertsPerHour: Int = 15,

    /**
     * Forced mode. null = auto-detect cascade.
     */
    val forcedMode: RideMode? = null,

    /** Min carb deficit (grams) before pre-effort fueling prompt fires */
    val fuelingAlertThresholdGrams: Int = 10,

    /** Target carb intake g/hr (40-60 endurance, 60-90 intervals) */
    val carbTargetGramsPerHour: Int = 60,
    /** Estimated grams per fuel serving (gel ≈25, bar ≈40, chew ≈8) */
    val carbsPerFuelServing: Int = 25,
    /** Hydration reminder interval in minutes */
    val drinkReminderMinutes: Int = 20,

    /** Gradient threshold (%) to detect climb route */
    val climbRouteGradientThresholdPct: Float = 4f,
    /** Min elevation gain (m) to classify route as climb-focused */
    val climbRouteMinGainM: Float = 1000f,

    /** Cadence below which cadence-dropping alert fires during effort */
    val minEffortCadenceRpm: Int = 75,

    // ----------------------------------------------------------------
    // AI coaching
    // ----------------------------------------------------------------
    /** Which LLM provider to use for message generation */
    val llmProvider: LlmProvider = LlmProvider.DISABLED,
    /** Gemini API key — get free at aistudio.google.com */
    val geminiApiKey: String = "",
    /** Mercury-2 API key — get 10M free tokens at platform.inceptionlabs.ai */
    val mercuryApiKey: String = "",

    // ----------------------------------------------------------------
    // Analytics
    // ----------------------------------------------------------------
    /** Opt-in consent for anonymous product analytics (PostHog) */
    val analyticsEnabled: Boolean = false,
)
