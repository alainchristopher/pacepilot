package io.hammerhead.pacepilot

import io.hammerhead.pacepilot.model.ActiveMode
import io.hammerhead.pacepilot.model.IntervalPhase
import io.hammerhead.pacepilot.model.ModeSource
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.RideMode
import io.hammerhead.pacepilot.model.TargetType
import io.hammerhead.pacepilot.model.WorkoutState

/** Base ride context for tests — recording, 30 min in, FTP 250, max HR 180 */
fun baseContext(
    mode: RideMode = RideMode.WORKOUT,
    rideElapsedSec: Long = 1800,
    ftp: Int = 250,
    maxHr: Int = 180,
): RideContext = RideContext(
    activeMode = ActiveMode(mode, ModeSource.AUTO_DETECTED),
    isRecording = true,
    rideElapsedSec = rideElapsedSec,
    ftp = ftp,
    maxHr = maxHr,
)

object TestHelpers {
    fun buildContext(
        mode: RideMode,
        power30sAvg: Int = 200,
        workoutActive: Boolean = false,
    ): RideContext = RideContext(
        activeMode = ActiveMode(mode, ModeSource.AUTO_DETECTED),
        isRecording = true,
        rideElapsedSec = 1800L,
        ftp = 250,
        maxHr = 185,
        power30sAvg = power30sAvg,
        powerWatts = power30sAvg,
        heartRateBpm = 140,
        workout = WorkoutState(isActive = workoutActive),
    )
}

/** Workout context with EFFORT phase, power targets 230-250W */
fun effortContext(
    power30sAvg: Int = 240,
    powerWatts: Int = 240,
    targetLow: Int = 230,
    targetHigh: Int = 250,
    remainingSec: Int = 300,
    elapsedSec: Int = 120,
    step: Int = 1,
    totalSteps: Int = 7,
    ftp: Int = 250,
    nextPhase: IntervalPhase? = IntervalPhase.RECOVERY,
): RideContext = baseContext(ftp = ftp).copy(
    powerWatts = powerWatts,
    power30sAvg = power30sAvg,
    workout = WorkoutState(
        isActive = true,
        currentPhase = IntervalPhase.EFFORT,
        currentStep = step,
        totalSteps = totalSteps,
        intervalElapsedSec = elapsedSec,
        intervalRemainingSec = remainingSec,
        targetType = TargetType.POWER,
        targetLow = targetLow,
        targetHigh = targetHigh,
        nextPhase = nextPhase,
    ),
)

/** Recovery context, mid-recovery */
fun recoveryContext(
    power30sAvg: Int = 120,
    hrBpm: Int = 145,
    remainingSec: Int = 180,
    elapsedSec: Int = 60,
    step: Int = 2,
    totalSteps: Int = 7,
    ftp: Int = 250,
    maxHr: Int = 180,
    nextPhase: IntervalPhase? = IntervalPhase.EFFORT,
): RideContext = baseContext(ftp = ftp, maxHr = maxHr).copy(
    power30sAvg = power30sAvg,
    heartRateBpm = hrBpm,
    workout = WorkoutState(
        isActive = true,
        currentPhase = IntervalPhase.RECOVERY,
        currentStep = step,
        totalSteps = totalSteps,
        intervalElapsedSec = elapsedSec,
        intervalRemainingSec = remainingSec,
        targetType = TargetType.POWER,
        targetLow = 80,
        targetHigh = 120,
        nextPhase = nextPhase,
    ),
)
