package io.hammerhead.pacepilot.model

enum class RideMode {
    /** Structured workout active — coach interval compliance */
    WORKOUT,

    /** Route loaded with significant climbing — climb-focused pacing */
    CLIMB_FOCUSED,

    /** Route loaded, flat/rolling — endurance discipline coaching */
    ENDURANCE,

    /** No route/workout — observe first 10 min, infer intent */
    ADAPTIVE,

    /** Inferred from sustained Z1 in adaptive mode */
    RECOVERY,
}

/** Why the current mode was set */
enum class ModeSource {
    AUTO_DETECTED,
    MANUAL_OVERRIDE,
    TRANSITION,  // dynamic transition during ride
}

data class ActiveMode(
    val mode: RideMode,
    val source: ModeSource,
    val previousMode: RideMode? = null,
    /** For CLIMB_FOCUSED temp transitions — mode to revert to when descent begins */
    val revertToMode: RideMode? = null,
)
