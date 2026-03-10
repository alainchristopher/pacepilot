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

    // Fueling (NomRide integration — optional)
    val carbDeficitGrams: Int? = null,      // null if NomRide not available
    val timeSinceLastFuelSec: Long? = null,

    // Silence / suppression
    val silencedUntilSec: Long = 0L,        // epoch second, 0 = not silenced
    val lastFuelingAckSec: Long = 0L,       // when rider last confirmed fueling

    // Computed helpers (derived, not from streams)
    val inFirstIntervalOfSession: Boolean = false,
    val minutesInZ1Sustained: Float = 0f,   // for adaptive recovery detection
)

val RideContext.currentMode: RideMode get() = activeMode.mode
val RideContext.isWorkoutMode: Boolean get() = currentMode == RideMode.WORKOUT
val RideContext.isWorkoutActive: Boolean get() = workout.isActive
