package io.hammerhead.pacepilot.ai

import io.hammerhead.pacepilot.history.CueBank
import io.hammerhead.pacepilot.history.RideHistory
import io.hammerhead.pacepilot.model.RuleId
import io.hammerhead.pacepilot.settings.UserSettings
import timber.log.Timber

/**
 * Generates offline cue bank using the configured AI provider (Wi-Fi required).
 */
class CueBankGenerator(
    private val settings: UserSettings,
    private val history: RideHistory,
) {
    suspend fun generate(): CueBank {
        val client = buildClient() ?: throw IllegalStateException("No AI provider configured")
        val lang = settings.coachingLanguage
        val system = CoachingContextBuilder.systemPromptForLanguage(lang) +
            "\nGenerate ONE short coaching cue per request. Max 12 words. No quotes."
        val stable = CoachingContextBuilder.buildStableContext(history, lang)

        client.initRide(system, stable)

        val ruleIds = listOf(
            RuleId.ZONE_DRIFT, RuleId.FUEL_TIME_BASED, RuleId.DRINK_REMINDER,
            RuleId.HR_DECOUPLING, RuleId.PROTECT_LAST_HOUR, RuleId.PACING_CONSISTENT,
            RuleId.CLIMB_POWER_CEILING, RuleId.PRE_CLIMB_PREP, RuleId.CLIMB_ENTRY,
            RuleId.POWER_ABOVE_TARGET, RuleId.POWER_BELOW_TARGET,
            RuleId.RACE_POWER_HIGH, RuleId.RACE_POWER_LOW, RuleId.RACE_VI_HIGH,
            RuleId.RACE_FUEL, RuleId.RACE_DRINK, RuleId.RACE_FINISH_75,
            RuleId.RACE_FINISH_90, RuleId.RACE_NEGATIVE_SPLIT, RuleId.RACE_T2_PREP,
        )

        val messages = mutableMapOf<String, List<String>>()
        for (ruleId in ruleIds) {
            val cues = mutableListOf<String>()
            repeat(4) { idx ->
                val prompt = buildString {
                    append("Rule: $ruleId. Variant ${idx + 1}/4.")
                    append(" Rider profile in context. Give a unique mid-ride cue for this situation.")
                }
                val fallback = "Coach cue $idx"
                val text = client.generate(prompt, fallback).trim()
                if (text.isNotBlank() && text != fallback) {
                    cues += text.take(100)
                }
            }
            if (cues.isNotEmpty()) messages[ruleId] = cues
            Timber.i("CueBankGenerator: $ruleId → ${cues.size} cues")
        }

        client.endRide()

        return CueBank(
            generatedAtEpochMs = System.currentTimeMillis(),
            language = lang,
            provider = settings.llmProvider.name,
            messages = messages,
        )
    }

    private fun buildClient(): AiCoachingClient? = when (settings.llmProvider) {
        LlmProvider.GEMINI ->
            if (settings.geminiApiKey.isNotBlank()) GeminiClient(settings.geminiApiKey) else null
        LlmProvider.MERCURY ->
            if (settings.mercuryApiKey.isNotBlank()) MercuryClient(settings.mercuryApiKey) else null
        LlmProvider.DISABLED -> null
    }
}
