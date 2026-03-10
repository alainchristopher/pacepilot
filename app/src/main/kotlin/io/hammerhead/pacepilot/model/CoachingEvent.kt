package io.hammerhead.pacepilot.model

enum class CoachingPriority(val level: Int) {
    CRITICAL(100),  // safety / immediately actionable (power hugely over target)
    HIGH(75),       // interval transitions, session-level warnings
    MEDIUM(50),     // compliance nudges, fueling
    LOW(25),        // positive reinforcement, general info
    INFO(10),       // mode changes, session start
}

enum class AlertStyle {
    COACHING,   // orange — performance coaching
    FUEL,       // green — fueling reminder
    WARNING,    // red — overexertion / safety
    POSITIVE,   // green — reinforcement
    INFO,       // neutral — mode changes
}

/**
 * A single coaching decision output from any rule.
 * Rules return null if their condition is not met.
 */
data class CoachingEvent(
    val ruleId: String,
    val message: String,
    val priority: CoachingPriority,
    val alertStyle: AlertStyle = AlertStyle.COACHING,
    val cooldownOverrideSec: Int? = null, // null = use CooldownManager default for current mode/phase
    val suppressIfFiredInLastSec: Int? = null, // additional per-rule suppression
    val requiresAck: Boolean = false,
)

/** Well-known rule IDs */
object RuleId {
    // Workout: pre-interval
    const val PRE_INTERVAL_ALERT = "pre_interval_alert"
    const val PRE_INTERVAL_FUELING = "pre_interval_fueling"
    const val FIRST_INTERVAL = "first_interval"

    // Workout: during effort
    const val POWER_ABOVE_TARGET = "power_above_target"
    const val POWER_BELOW_TARGET = "power_below_target"
    const val POWER_ON_TARGET = "power_on_target"
    const val INTERVAL_COUNTDOWN = "interval_countdown"
    const val CADENCE_DROPPING = "cadence_dropping"
    const val HR_CEILING_EXCEEDED = "hr_ceiling_exceeded"

    // Workout: recovery
    const val RECOVERY_NOT_RECOVERING = "recovery_not_recovering"
    const val HR_NOT_DROPPING = "hr_not_dropping"
    const val RECOVERING_WELL = "recovering_well"
    const val RECOVERY_FUELING_WINDOW = "recovery_fueling_window"

    // Workout: session-level
    const val LAST_INTERVAL_MOTIVATION = "last_interval_motivation"
    const val POWER_FADING_TREND = "power_fading_trend"
    const val RECOVERY_QUALITY_DECLINING = "recovery_quality_declining"
    const val SESSION_COMPLETE = "session_complete"

    // Endurance
    const val EARLY_RIDE_CHECK = "early_ride_check"
    const val ZONE_DRIFT = "zone_drift"
    const val FUEL_TIME_BASED = "fuel_time_based"
    const val HR_DECOUPLING = "hr_decoupling"
    const val PROTECT_LAST_HOUR = "protect_last_hour"
    const val PACING_CONSISTENT = "pacing_consistent"

    // Climb
    const val CLIMB_ENTRY = "climb_entry"
    const val CLIMB_POWER_CEILING = "climb_power_ceiling"
    const val CLIMB_CADENCE_DROP = "climb_cadence_drop"
    const val CLIMB_SUMMIT_NEAR = "climb_summit_near"
    const val CLIMB_DESCENT = "climb_descent"
    const val MULTI_CLIMB_FATIGUE = "multi_climb_fatigue"

    // Adaptive
    const val ADAPTIVE_OBSERVING = "adaptive_observing"
    const val ADAPTIVE_ENDURANCE = "adaptive_endurance"
    const val ADAPTIVE_RECOVERY = "adaptive_recovery"
    const val ADAPTIVE_UNSTRUCTURED = "adaptive_unstructured"
    const val FUEL_FIRST_30MIN = "fuel_first_30min"

    // Mode
    const val MODE_DETECTED = "mode_detected"
    const val MODE_OVERRIDE = "mode_override"
}
