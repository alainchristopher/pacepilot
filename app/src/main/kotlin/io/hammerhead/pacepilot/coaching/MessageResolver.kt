package io.hammerhead.pacepilot.coaching

import io.hammerhead.pacepilot.history.CueBank
import io.hammerhead.pacepilot.model.CoachingEvent
import io.hammerhead.pacepilot.model.RideContext

enum class CoachingMessageSource {
    RULES,
    CUE_BANK,
    AI,
}

/**
 * Resolves the rider-visible message: cue bank → rotating pool → rule default.
 */
class MessageResolver(
    private val cueBankProvider: () -> CueBank?,
) {
    @Volatile var lastSource: CoachingMessageSource = CoachingMessageSource.RULES
        private set

    fun resolve(event: CoachingEvent, ctx: RideContext): String {
        val bank = cueBankProvider()
        bank?.messages?.get(event.ruleId)?.let { cues ->
            if (cues.isNotEmpty()) {
                lastSource = CoachingMessageSource.CUE_BANK
                return pick(cues, event.ruleId, ctx)
            }
        }
        OfflineMessagePools.variants(event.ruleId)?.let { variants ->
            if (variants.isNotEmpty()) {
                lastSource = CoachingMessageSource.RULES
                return pick(variants, event.ruleId, ctx)
            }
        }
        lastSource = CoachingMessageSource.RULES
        return event.message
    }

    fun markAiSource() {
        lastSource = CoachingMessageSource.AI
    }

    private fun pick(variants: List<String>, ruleId: String, ctx: RideContext): String {
        if (variants.size == 1) return variants.first()
        val bucket = (ctx.rideElapsedSec / 300L + ruleId.hashCode()).toInt()
        return variants[kotlin.math.abs(bucket) % variants.size]
    }
}
