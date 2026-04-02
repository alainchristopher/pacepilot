package io.hammerhead.pacepilot.coaching

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.pacepilot.R
import io.hammerhead.pacepilot.ai.AiCoachingClient
import io.hammerhead.pacepilot.ai.CoachingContextBuilder
import io.hammerhead.pacepilot.ai.GeminiClient
import io.hammerhead.pacepilot.ai.LlmProvider
import io.hammerhead.pacepilot.ai.MercuryClient
import io.hammerhead.pacepilot.ai.RideNarrative
import io.hammerhead.pacepilot.history.RideHistory
import io.hammerhead.pacepilot.model.AlertStyle
import io.hammerhead.pacepilot.model.CoachingEvent
import io.hammerhead.pacepilot.model.CoachingPriority
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.RideMode
import io.hammerhead.pacepilot.model.currentMode
import io.hammerhead.pacepilot.settings.SettingsRepository
import io.hammerhead.pacepilot.settings.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.ArrayDeque

/**
 * The main coaching loop.
 *
 * Every [TICK_INTERVAL_MS] ms, evaluates applicable rules for the current mode,
 * picks the highest-priority event that passes cooldown, then:
 *
 *   (AI disabled) → dispatch static rule message immediately.
 *
 *   (AI enabled)  → dispatch static message immediately (zero latency for rider),
 *                   launch async Gemini call with full context (live state +
 *                   ride narrative + cached history), re-dispatch upgraded message
 *                   when it arrives (still within autoDismiss window).
 *
 * The Gemini context cache is created once per ride (stable system prompt +
 * rider history) and deleted on ride stop. Per-event calls only send live data.
 *
 * RideNarrative accumulates the story of the current ride so the LLM knows
 * what happened earlier — not just the current snapshot.
 */
class CoachingEngine(
    private val karooSystem: KarooSystemService,
    private val rideContext: StateFlow<RideContext>,
    private val settingsRepo: SettingsRepository,
    private val historyProvider: () -> RideHistory,
    private val scope: CoroutineScope,
    private val onEventDispatched: ((CoachingEvent, String) -> Unit)? = null,
) {
    private var tickJob: Job? = null
    private var narrativeJob: Job? = null
    private var cooldown: CooldownManager? = null

    // AI layer — polymorphic; null means rules-only mode
    private var aiClient: AiCoachingClient? = null
    val narrative = RideNarrative()
    private val alertTimesSec = ArrayDeque<Long>()

    companion object {
        const val TICK_INTERVAL_MS = 5_000L
        private const val NARRATIVE_UPDATE_MS = 1_000L
    }

    fun start() {
        cooldown = CooldownManager(settingsRepo.current.cooldownMultiplier)
        narrative.reset()

        val settings = settingsRepo.current
        aiClient = when (settings.llmProvider) {
            LlmProvider.GEMINI -> if (settings.geminiApiKey.isNotBlank()) GeminiClient(settings.geminiApiKey) else null
            LlmProvider.MERCURY -> if (settings.mercuryApiKey.isNotBlank()) MercuryClient(settings.mercuryApiKey) else null
            LlmProvider.DISABLED -> null
        }
        aiClient?.let { client ->
            // Init async — coaching starts immediately with static fallback until ready
            scope.launch {
                val stableContext = CoachingContextBuilder.buildStableContext(historyProvider())
                client.initRide(CoachingContextBuilder.SYSTEM_PROMPT, stableContext)
                Timber.i("CoachingEngine: AI provider ${settings.llmProvider} ready")
            }
        }

        narrativeJob = scope.launch {
            while (true) {
                narrative.onContext(rideContext.value)
                delay(NARRATIVE_UPDATE_MS)
            }
        }

        tickJob = scope.launch {
            while (true) {
                tick()
                delay(TICK_INTERVAL_MS)
            }
        }

        Timber.i("CoachingEngine: started (ai=${settings.llmProvider})")
    }

    fun stop() {
        tickJob?.cancel(); tickJob = null
        narrativeJob?.cancel(); narrativeJob = null
        cooldown?.reset()

        val client = aiClient
        aiClient = null
        if (client != null) {
            scope.launch { client.endRide() }
        }
        narrative.reset()
        alertTimesSec.clear()

        Timber.i("CoachingEngine: stopped")
    }

    fun onSettingsChanged() {
        val settings = settingsRepo.current
        val needsClient = when (settings.llmProvider) {
            LlmProvider.GEMINI -> settings.geminiApiKey.isNotBlank()
            LlmProvider.MERCURY -> settings.mercuryApiKey.isNotBlank()
            LlmProvider.DISABLED -> false
        }
        if (needsClient && aiClient == null) {
            val newClient: AiCoachingClient = when (settings.llmProvider) {
                LlmProvider.GEMINI -> GeminiClient(settings.geminiApiKey)
                LlmProvider.MERCURY -> MercuryClient(settings.mercuryApiKey)
                LlmProvider.DISABLED -> return
            }
            aiClient = newClient
            scope.launch {
                val stableContext = CoachingContextBuilder.buildStableContext(historyProvider())
                newClient.initRide(CoachingContextBuilder.SYSTEM_PROMPT, stableContext)
            }
        } else if (!needsClient) {
            val old = aiClient
            aiClient = null
            if (old != null) scope.launch { old.endRide() }
        }
    }

    // ----------------------------------------------------------------
    // Tick
    // ----------------------------------------------------------------

    private fun tick() {
        val settings = settingsRepo.current
        if (!settings.alertsEnabled) return

        val ctx = rideContext.value
        if (!ctx.isRecording) return

        val candidates = gatherCandidates(ctx, settings)
        if (candidates.isEmpty()) return

        val cd = cooldown ?: return
        val nowSec = System.currentTimeMillis() / 1000
        val toFire = candidates
            .sortedByDescending { it.priority.level }
            .firstOrNull { event ->
                if (!settings.fuelingAlertsEnabled && event.alertStyle == AlertStyle.FUEL) return@firstOrNull false
                cd.canFire(event, ctx) && passesAlertPolicy(event, settings, nowSec)
            } ?: return

        cd.recordFired(toFire.ruleId, toFire.priority)
        recordAlert(nowSec)

        val client = aiClient
        if (client != null) {
            // Show static message immediately — rider gets feedback in <1ms
            dispatch(toFire, toFire.message)

            // Upgrade async — when AI responds before auto-dismiss, rider sees smarter message
            scope.launch {
                val livePrompt = CoachingContextBuilder.buildLivePrompt(toFire, ctx, narrative)
                val aiMessage = client.generate(livePrompt, toFire.message)
                if (aiMessage != toFire.message) {
                    Timber.d("CoachingEngine: AI upgraded \"${toFire.message}\" → \"$aiMessage\"")
                    dispatch(toFire, aiMessage)
                }
            }
        } else {
            dispatch(toFire, toFire.message)
        }
    }

    private fun gatherCandidates(ctx: RideContext, settings: UserSettings): List<CoachingEvent> =
        when (ctx.currentMode) {
            RideMode.WORKOUT -> WorkoutCoachingRules.evaluateAll(
                ctx = ctx,
                settingsMinCadence = settings.minEffortCadenceRpm,
                fuelingThresholdGrams = settings.fuelingAlertThresholdGrams,
            )
            RideMode.ENDURANCE -> EnduranceCoachingRules.evaluateAll(ctx) +
                if (ctx.isOnClimb) ClimbCoachingRules.evaluateAll(ctx) else emptyList()
            RideMode.CLIMB_FOCUSED -> ClimbCoachingRules.evaluateAll(ctx) +
                listOfNotNull(
                    EnduranceCoachingRules.fuelTimeBasedReminder(ctx),
                    EnduranceCoachingRules.drinkReminder(ctx, settings.drinkReminderMinutes),
                )
            RideMode.ADAPTIVE, RideMode.RECOVERY -> AdaptiveCoachingRules.evaluateAll(ctx)
        }

    private fun dispatch(event: CoachingEvent, message: String) {
        // Karoo screen truncates around 30 chars on the narrow display
        val detail = if (message.length > 30) message.take(28) + "…" else message
        Timber.i("CoachingEngine: rule=${event.ruleId} priority=${event.priority} → \"$detail\"")
        val (bgColor, textColor, title) = alertAppearance(event.alertStyle)
        karooSystem.dispatch(
            InRideAlert(
                id = "pp_${event.ruleId}",
                icon = R.drawable.ic_pacepilot,
                title = title,
                detail = detail,
                autoDismissMs = autoDismissMs(event.priority),
                backgroundColor = bgColor,
                textColor = textColor,
            )
        )
        onEventDispatched?.invoke(event, detail)
    }

    private fun passesAlertPolicy(
        event: CoachingEvent,
        settings: UserSettings,
        nowSec: Long,
    ): Boolean {
        // Critical safety-type prompts always bypass policy limits.
        if (event.priority == CoachingPriority.CRITICAL) return true

        // Keep only 1h window.
        while (alertTimesSec.isNotEmpty() && nowSec - alertTimesSec.first() > 3600) {
            alertTimesSec.removeFirst()
        }
        if (alertTimesSec.size >= settings.maxAlertsPerHour) {
            Timber.d("CoachingEngine: policy block maxAlertsPerHour=%d", settings.maxAlertsPerHour)
            return false
        }
        val last = alertTimesSec.lastOrNull()
        if (last != null && nowSec - last < settings.minAlertGapSec) {
            Timber.d("CoachingEngine: policy block minAlertGapSec=%d", settings.minAlertGapSec)
            return false
        }
        return true
    }

    private fun recordAlert(nowSec: Long) {
        alertTimesSec.addLast(nowSec)
        while (alertTimesSec.isNotEmpty() && nowSec - alertTimesSec.first() > 3600) {
            alertTimesSec.removeFirst()
        }
    }

    private fun alertAppearance(style: AlertStyle): Triple<Int, Int, String> = when (style) {
        AlertStyle.COACHING -> Triple(R.color.alert_bg_coaching, R.color.alert_text_coaching, "Coach")
        AlertStyle.FUEL -> Triple(R.color.alert_bg_fuel, R.color.alert_text_fuel, "Fuel Up")
        AlertStyle.WARNING -> Triple(R.color.alert_bg_warning, R.color.alert_text_warning, "Alert")
        AlertStyle.POSITIVE -> Triple(R.color.alert_bg_positive, R.color.alert_text_positive, "Nice")
        AlertStyle.INFO -> Triple(R.color.alert_bg_coaching, R.color.alert_text_coaching, "PacePilot")
    }

    private fun autoDismissMs(priority: CoachingPriority): Long = when (priority) {
        CoachingPriority.CRITICAL -> 12_000L
        CoachingPriority.HIGH -> 10_000L
        CoachingPriority.MEDIUM -> 8_000L
        CoachingPriority.LOW -> 6_000L
        CoachingPriority.INFO -> 6_000L
    }
}
