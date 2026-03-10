package io.hammerhead.pacepilot.model

/**
 * Complete snapshot of all ride state at a point in time.
 * This is the single input to every coaching rule.
 */
data class RideContext(
    // Mode
    val activeMode: ActiveMode = ActiveMode(RideMode.ADAPTIVE, ModeSource.AUTO_DETECTED),

    // Ride lifecycle
    val isRecording: Boolean = false,
    val rideElapsedSec: Long = 0L,

    // Power
    val powerWatts: Int = 0,
    val power5sAvg: Int = 0,
    val power30sAvg: Int = 0,
    val power3minAvg: Int = 0,
    val normalizedPower: Int = 0,
    val variabilityIndex: Float = 1f,    // NP / avg power
    val powerZone: Int = 0,             // 1-7
    val ftp: Int = 250,                 // from UserProfile or settings

    // Heart rate
    val heartRateBpm: Int = 0,
    val hrZone: Int = 0,                // 1-5
    val hrRecoveryRate: Float = 0f,     // bpm/sec drop rate
    val hrDecouplingPct: Float = 0f,    // % power:HR ratio drift
    val maxHr: Int = 185,
    // Karoo user profile HR zones (min/max bpm per zone, index 0 = Z1)
    // Empty = fall back to % maxHr calculation
    val hrZoneBounds: List<Pair<Int, Int>> = emptyList(),

    // Cadence
    val cadenceRpm: Int = 0,

    // Speed & distance
    val speedKmh: Float = 0f,
    val distanceKm: Float = 0f,

    // Elevation & terrain
    val elevationGradePct: Float = 0f,
    val elevationGainM: Float = 0f,
    val distanceToClimbTopM: Float? = null,    // from navigation / 7Climb
    val climbNumber: Int = 0,                  // which climb in route
    val totalClimbsOnRoute: Int = 0,
    val isOnClimb: Boolean = false,
    val isDescending: Boolean = false,

    // Navigation
    val hasRoute: Boolean = false,
    val routeTotalElevationGainM: Float = 0f,
    val routeSteeplyGradedPct: Float = 0f,  // % of route above 4% grade

    // Workout state
    val workout: WorkoutState = WorkoutState(),

    // Fueling & hydration
    val carbsConsumedGrams: Int = 0,        // total carbs consumed this ride
    val carbTargetGrams: Int = 0,           // target carbs for elapsed time
    val carbDeficitGrams: Int = 0,          // target - consumed (positive = deficit)
    val lastFuelAckEpochSec: Long = 0L,     // last time rider tapped "Ate"
    val lastDrinkAckEpochSec: Long = 0L,    // last time rider tapped "Drank"
    val fuelAckCount: Int = 0,              // how many times rider has eaten
    val drinkAckCount: Int = 0,             // how many times rider has drunk

    // Silence / suppression
    val silencedUntilSec: Long = 0L,        // epoch second, 0 = not silenced

    // Computed helpers (derived, not from streams)
    val inFirstIntervalOfSession: Boolean = false,
    val minutesInZ1Sustained: Float = 0f,   // for adaptive recovery detection
)

val RideContext.currentMode: RideMode get() = activeMode.mode
val RideContext.isWorkoutMode: Boolean get() = currentMode == RideMode.WORKOUT
val RideContext.isWorkoutActive: Boolean get() = workout.isActive

/** True when a structured HR-based workout is actively running */
val RideContext.isHrBasedWorkout: Boolean
    get() = workout.isActive && workout.targetType == io.hammerhead.pacepilot.model.TargetType.HEART_RATE

/**
 * Look up the HR zone bounds for [zone] (1-indexed).
 * Uses Karoo profile zones when available, falls back to % maxHr.
 */
fun RideContext.hrZoneBoundsFor(zone: Int): Pair<Int, Int> {
    val idx = zone - 1
    if (hrZoneBounds.size > idx && idx >= 0) return hrZoneBounds[idx]
    // fallback: % maxHr (5-zone model)
    val lower = when (zone) {
        1 -> 0; 2 -> maxHr * 61 / 100; 3 -> maxHr * 71 / 100
        4 -> maxHr * 81 / 100; 5 -> maxHr * 91 / 100; else -> 0
    }
    val upper = when (zone) {
        1 -> maxHr * 60 / 100; 2 -> maxHr * 70 / 100; 3 -> maxHr * 80 / 100
        4 -> maxHr * 90 / 100; else -> maxHr
    }
    return lower to upper
}
