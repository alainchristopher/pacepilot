package io.hammerhead.pacepilot.analytics

import android.app.Application
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import io.hammerhead.pacepilot.ai.LlmProvider
import io.hammerhead.pacepilot.coaching.CoachingStats
import io.hammerhead.pacepilot.model.RideMode
import io.hammerhead.pacepilot.settings.SettingsRepository
import timber.log.Timber

/**
 * Opt-in product analytics via PostHog.
 *
 * Events are only sent when the user has explicitly enabled analytics.
 * No PII is collected — only ride metrics and feature usage.
 */
object AnalyticsManager {
    private const val POSTHOG_API_KEY = "phc_kmJYj4vzaXStS7vfdCSBsaWSatNoULgDygVx9y95DDZG"
    private const val POSTHOG_HOST = "https://us.i.posthog.com"

    private var initialized = false
    private var settingsRepo: SettingsRepository? = null

    fun init(application: Application, settingsRepo: SettingsRepository) {
        if (initialized) return
        this.settingsRepo = settingsRepo

        val config = PostHogAndroidConfig(
            apiKey = POSTHOG_API_KEY,
            host = POSTHOG_HOST,
        ).apply {
            captureApplicationLifecycleEvents = false
            captureDeepLinks = false
            captureScreenViews = false
            debug = false
        }

        PostHogAndroid.setup(application, config)
        initialized = true
        Timber.i("AnalyticsManager: initialized")

        // Set initial opt-out state
        updateOptOut()
    }

    fun updateOptOut() {
        val enabled = settingsRepo?.current?.analyticsEnabled ?: false
        if (enabled) {
            PostHog.optIn()
            Timber.i("AnalyticsManager: opted IN")
        } else {
            PostHog.optOut()
            Timber.i("AnalyticsManager: opted OUT")
        }
    }

    private fun isEnabled(): Boolean = settingsRepo?.current?.analyticsEnabled == true

    // ----------------------------------------------------------------
    // Tier 1: Core Value Events
    // ----------------------------------------------------------------

    fun trackRideStarted(mode: RideMode, aiProvider: LlmProvider) {
        if (!isEnabled()) return
        PostHog.capture(
            event = "ride_started",
            properties = mapOf(
                "initial_mode" to mode.name,
                "ai_provider" to aiProvider.name,
            )
        )
    }

    fun trackRideCompleted(
        durationMin: Int,
        distanceKm: Float,
        elevationM: Float,
        mode: RideMode,
        stats: CoachingStats,
        aiProvider: LlmProvider,
    ) {
        if (!isEnabled()) return
        val aiUpgradePct = if (stats.alertsFired > 0) {
            (stats.aiUpgrades * 100 / stats.alertsFired)
        } else 0

        PostHog.capture(
            event = "ride_completed",
            properties = mapOf(
                "duration_min" to durationMin,
                "distance_km" to distanceKm,
                "elevation_m" to elevationM,
                "ride_mode" to mode.name,
                "alerts_fired" to stats.alertsFired,
                "ai_upgrades" to stats.aiUpgrades,
                "ai_failures" to stats.aiFailures,
                "ai_upgrade_pct" to aiUpgradePct,
                "suppressed_total" to (stats.suppressedByPolicy + stats.suppressedByCooldown),
                "ai_provider" to aiProvider.name,
            )
        )
    }

    fun trackAlertShown(
        ruleId: String,
        aiUpgraded: Boolean,
        latencyMs: Long,
    ) {
        if (!isEnabled()) return
        PostHog.capture(
            event = "alert_shown",
            properties = mapOf(
                "rule_id" to ruleId,
                "ai_upgraded" to aiUpgraded,
                "latency_ms" to latencyMs,
            )
        )
    }

    fun trackAlertInteracted(
        ruleId: String,
        action: String, // snooze, ack_fuel, ack_drink, dismiss
    ) {
        if (!isEnabled()) return
        PostHog.capture(
            event = "alert_interacted",
            properties = mapOf(
                "rule_id" to ruleId,
                "action" to action,
            )
        )
    }

    // ----------------------------------------------------------------
    // Tier 2: Quality Events
    // ----------------------------------------------------------------

    fun trackAiCallFailed(
        provider: LlmProvider,
        errorType: String,
        latencyMs: Long,
    ) {
        if (!isEnabled()) return
        PostHog.capture(
            event = "ai_call_failed",
            properties = mapOf(
                "provider" to provider.name,
                "error_type" to errorType,
                "latency_ms" to latencyMs,
            )
        )
    }

    fun trackSettingsChanged(
        field: String,
        newValue: String,
    ) {
        if (!isEnabled()) return
        PostHog.capture(
            event = "settings_changed",
            properties = mapOf(
                "field" to field,
                "new_value" to newValue,
            )
        )
    }

    fun trackModeTransition(
        fromMode: RideMode,
        toMode: RideMode,
        trigger: String, // auto, manual
    ) {
        if (!isEnabled()) return
        PostHog.capture(
            event = "mode_transition",
            properties = mapOf(
                "from_mode" to fromMode.name,
                "to_mode" to toMode.name,
                "trigger" to trigger,
            )
        )
    }

    // ----------------------------------------------------------------
    // User Properties (set once)
    // ----------------------------------------------------------------

    fun identifyUser(ftpWatts: Int, maxHr: Int) {
        if (!isEnabled()) return
        // Use anonymous ID, just set user properties
        PostHog.identify(
            distinctId = PostHog.distinctId(),
            userProperties = mapOf(
                "ftp_watts" to ftpWatts,
                "max_hr" to maxHr,
            )
        )
    }
}
