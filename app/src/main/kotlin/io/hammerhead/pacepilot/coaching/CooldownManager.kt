package io.hammerhead.pacepilot.coaching

import io.hammerhead.pacepilot.model.CoachingPriority
import io.hammerhead.pacepilot.model.IntervalPhase
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.RideMode
import io.hammerhead.pacepilot.model.currentMode

/**
 * Manages per-rule and global coaching cooldowns.
 *
 * Global cooldown is mode/phase-aware per spec:
 * - Effort intervals: 60s
 * - Recovery intervals: 120s
 * - Warmup/cooldown intervals: 180s
 * - Endurance/Climb/Adaptive: 180-300s
 *
 * Per-rule suppression prevents the same rule from firing too frequently
 * (each CoachingEvent carries its own suppressIfFiredInLastSec).
 */
class CooldownManager(
    private val cooldownMultiplier: Float = 1.0f,
    private val clockProvider: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    /** epoch-second of last fired event */
    private var lastGlobalFireSec: Long = 0L

    /** Per-rule: epoch-second of last fire */
    private val lastFireByRule = mutableMapOf<String, Long>()

    fun canFire(event: io.hammerhead.pacepilot.model.CoachingEvent, ctx: RideContext): Boolean {
        val nowSec = clockProvider()

        if (event.priority != CoachingPriority.CRITICAL) {
            val globalCooldown = globalCooldownSec(ctx)
            if (nowSec - lastGlobalFireSec < globalCooldown) {
                return false
            }
        }

        val ruleSuppress = event.suppressIfFiredInLastSec
        if (ruleSuppress != null) {
            val lastFire = lastFireByRule[event.ruleId] ?: 0L
            if (nowSec - lastFire < ruleSuppress) {
                return false
            }
        }

        if (nowSec < ctx.silencedUntilSec && event.priority != CoachingPriority.CRITICAL) {
            return false
        }

        return true
    }

    fun recordFired(ruleId: String, priority: CoachingPriority = CoachingPriority.MEDIUM) {
        val nowSec = clockProvider()
        if (priority != CoachingPriority.INFO) {
            lastGlobalFireSec = nowSec
        }
        lastFireByRule[ruleId] = nowSec
    }

    fun reset() {
        lastGlobalFireSec = 0L
        lastFireByRule.clear()
    }

    fun lastFiredSec(ruleId: String): Long = lastFireByRule[ruleId] ?: 0L

    /**
     * Global cooldown in seconds based on current mode and workout phase.
     * Multiplied by [cooldownMultiplier] from settings.
     */
    fun globalCooldownSec(ctx: RideContext): Long {
        val baseSec = when (ctx.currentMode) {
            RideMode.WORKOUT -> {
                when (ctx.workout.currentPhase) {
                    IntervalPhase.EFFORT -> 60L
                    IntervalPhase.RECOVERY -> 120L
                    IntervalPhase.WARMUP, IntervalPhase.COOLDOWN -> 180L
                    IntervalPhase.UNKNOWN -> 180L
                }
            }
            RideMode.CLIMB_FOCUSED -> 120L
            RideMode.ENDURANCE -> 120L
            RideMode.ADAPTIVE -> 120L
            RideMode.RECOVERY -> 180L
        }
        return (baseSec * cooldownMultiplier).toLong()
    }
}
