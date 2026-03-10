package io.hammerhead.pacepilot

import io.hammerhead.pacepilot.model.ActiveMode
import io.hammerhead.pacepilot.model.CoachingEvent
import io.hammerhead.pacepilot.model.IntervalPhase
import io.hammerhead.pacepilot.model.ModeSource
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.RideMode
import io.hammerhead.pacepilot.model.TargetType
import io.hammerhead.pacepilot.model.WorkoutState

/**
 * Builds sequences of RideContext snapshots for rule testing.
 *
 * Usage:
 *   val frames = RideSimulator.workout(ftp = 280)
 *       .effortInterval(targetLow = 252, targetHigh = 308, durationSec = 300) { powerWatts = 350 }
 *       .recoveryInterval(durationSec = 120) { powerWatts = 100 }
 *       .build()
 *
 * Then pass each frame to the rule functions and collect events.
 */
class RideSimulator private constructor(
    private val ftp: Int,
    private val maxHr: Int,
    private val mode: RideMode,
) {
    private val frames = mutableListOf<RideContext>()
    private var elapsedSec: Long = 0L
    private var currentStep = 0
    private var totalSteps = 0

    companion object {
        fun workout(ftp: Int = 280, maxHr: Int = 185): RideSimulator =
            RideSimulator(ftp, maxHr, RideMode.WORKOUT)

        fun endurance(ftp: Int = 280, maxHr: Int = 185): RideSimulator =
            RideSimulator(ftp, maxHr, RideMode.ENDURANCE)

        fun recovery(ftp: Int = 280, maxHr: Int = 185): RideSimulator =
            RideSimulator(ftp, maxHr, RideMode.RECOVERY)
    }

    /**
     * Add a flat interval segment (every second is the same context).
     * [configure] can override any field on the base context.
     */
    fun interval(
        phase: IntervalPhase,
        durationSec: Int,
        targetLow: Int? = null,
        targetHigh: Int? = null,
        configure: RideContext.() -> RideContext = { this },
    ): RideSimulator {
        totalSteps++
        val step = currentStep
        repeat(durationSec) { tick ->
            val remaining = durationSec - tick
            val ws = WorkoutState(
                isActive = true,
                currentStep = step,
                totalSteps = totalSteps,
                currentPhase = phase,
                intervalElapsedSec = tick,
                intervalRemainingSec = remaining,
                targetType = if (targetLow != null || targetHigh != null) TargetType.POWER else TargetType.NONE,
                targetLow = targetLow,
                targetHigh = targetHigh,
            )
            val base = RideContext(
                activeMode = ActiveMode(mode, ModeSource.AUTO_DETECTED),
                isRecording = true,
                rideElapsedSec = elapsedSec + tick,
                ftp = ftp,
                maxHr = maxHr,
                workout = ws,
            )
            frames.add(base.configure())
        }
        elapsedSec += durationSec
        currentStep++
        return this
    }

    fun effortInterval(
        targetLow: Int,
        targetHigh: Int,
        durationSec: Int = 300,
        configure: RideContext.() -> RideContext = { this },
    ) = interval(IntervalPhase.EFFORT, durationSec, targetLow, targetHigh, configure)

    fun recoveryInterval(
        durationSec: Int = 120,
        configure: RideContext.() -> RideContext = { this },
    ) = interval(IntervalPhase.RECOVERY, durationSec, configure = configure)

    fun warmupInterval(durationSec: Int = 600) =
        interval(IntervalPhase.WARMUP, durationSec)

    fun cooldownInterval(durationSec: Int = 300) =
        interval(IntervalPhase.COOLDOWN, durationSec)

    fun steadyState(
        durationSec: Int,
        configure: RideContext.() -> RideContext,
    ): RideSimulator {
        repeat(durationSec) { tick ->
            val base = RideContext(
                activeMode = ActiveMode(mode, ModeSource.AUTO_DETECTED),
                isRecording = true,
                rideElapsedSec = elapsedSec + tick,
                ftp = ftp,
                maxHr = maxHr,
            )
            frames.add(base.configure())
        }
        elapsedSec += durationSec
        return this
    }

    fun build(): List<RideContext> = frames.toList()
}

/**
 * Runs all coaching rule evaluators against a list of RideContext frames
 * and returns all events that fired, with their frame index.
 */
object RuleEvaluator {
    data class FiredEvent(val frameSec: Long, val event: CoachingEvent)

    fun evaluateAll(frames: List<RideContext>, minCadence: Int = 75): List<FiredEvent> {
        val results = mutableListOf<FiredEvent>()
        for (ctx in frames) {
            val events = io.hammerhead.pacepilot.coaching.WorkoutCoachingRules.evaluateAll(ctx, minCadence) +
                io.hammerhead.pacepilot.coaching.EnduranceCoachingRules.evaluateAll(ctx) +
                io.hammerhead.pacepilot.coaching.ClimbCoachingRules.evaluateAll(ctx) +
                io.hammerhead.pacepilot.coaching.AdaptiveCoachingRules.evaluateAll(ctx)
            events.forEach { results.add(FiredEvent(ctx.rideElapsedSec, it)) }
        }
        return results
    }

    fun evaluateWorkout(frames: List<RideContext>, minCadence: Int = 75): List<FiredEvent> =
        frames.flatMap { ctx ->
            io.hammerhead.pacepilot.coaching.WorkoutCoachingRules
                .evaluateAll(ctx, minCadence)
                .map { FiredEvent(ctx.rideElapsedSec, it) }
        }
}
