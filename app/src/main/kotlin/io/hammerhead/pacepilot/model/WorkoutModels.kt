package io.hammerhead.pacepilot.model

enum class IntervalPhase {
    WARMUP,
    EFFORT,
    RECOVERY,
    COOLDOWN,
    UNKNOWN,
}

enum class TargetType {
    POWER,
    HEART_RATE,
    RPE,
    NONE,
}

enum class WorkoutType {
    SWEET_SPOT,       // Z3-Z4, intervals > 8 min
    VO2_MAX,          // Z5+, 2-5 min with recovery
    THRESHOLD,        // Z4, 8-20 min
    ENDURANCE_SURGES, // Z2 base + Z4-5 spikes
    OVER_UNDER,       // alternating slightly above/below threshold
    RECOVERY_RIDE,    // Z1 only
    UNKNOWN,
}

/**
 * Spec for a single interval block as read from workout streams.
 * targetLow/targetHigh in watts (POWER) or bpm (HEART_RATE).
 */
data class IntervalSpec(
    val phase: IntervalPhase,
    val durationSec: Int,
    val targetType: TargetType,
    val targetLow: Int?,
    val targetHigh: Int?,
    val cadenceTargetMin: Int? = null,
    val cadenceTargetMax: Int? = null,
)

/**
 * Live workout state derived from Karoo WORKOUT_* streams.
 */
data class WorkoutState(
    val isActive: Boolean = false,
    val workoutType: WorkoutType = WorkoutType.UNKNOWN,

    // Current interval
    val currentPhase: IntervalPhase = IntervalPhase.UNKNOWN,
    val currentStep: Int = 0,       // 0-indexed
    val totalSteps: Int = 0,
    val intervalElapsedSec: Int = 0,
    val intervalRemainingSec: Int = 0,

    // Targets for current interval
    val targetType: TargetType = TargetType.NONE,
    val targetLow: Int? = null,
    val targetHigh: Int? = null,
    val cadenceTargetMin: Int? = null,
    val cadenceTargetMax: Int? = null,

    // Next interval preview
    val nextPhase: IntervalPhase? = null,
    val nextTargetLow: Int? = null,
    val nextTargetHigh: Int? = null,
    val nextDurationSec: Int? = null,

    // Session-level tracking
    val completedEffortCount: Int = 0,
    val totalEffortCount: Int = 0,
    val complianceScore: Float = 1f,       // 0-1: % time in target range this interval
    val recoveryQuality: Float = 1f,        // 0-1: HR recovery rate between efforts
    val powerFadingTrend: Boolean = false,  // avg power declining across sets
    val recoveryQualityDeclining: Boolean = false,

    // Per-set power tracking for trend analysis
    val effortAvgPowers: List<Int> = emptyList(),
    val recoveryDropRates: List<Float> = emptyList(), // bpm/sec HR drop rates
)

/** Emitted when the interval step changes */
data class IntervalTransitionEvent(
    val fromPhase: IntervalPhase,
    val toPhase: IntervalPhase,
    val fromStep: Int,
    val toStep: Int,
)
