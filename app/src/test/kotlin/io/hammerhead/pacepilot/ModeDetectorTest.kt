package io.hammerhead.pacepilot

import io.hammerhead.pacepilot.detection.ModeDetector
import io.hammerhead.pacepilot.model.ModeSource
import io.hammerhead.pacepilot.model.RideMode
import io.hammerhead.pacepilot.settings.UserSettings
import io.hammerhead.pacepilot.workout.WorkoutDetector
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ModeDetectorTest {

    private lateinit var detector: ModeDetector
    private val mockWorkoutDetector: WorkoutDetector = mockk(relaxed = true)

    @Before
    fun setup() {
        detector = ModeDetector(mockWorkoutDetector)
    }

    @Test
    fun `detects WORKOUT when workout is active`() {
        val ctx = baseContext()
        val result = detector.detect(ctx, UserSettings(), workoutActive = true)
        assertEquals(RideMode.WORKOUT, result.mode)
        assertEquals(ModeSource.AUTO_DETECTED, result.source)
    }

    @Test
    fun `detects CLIMB_FOCUSED when route has significant elevation gain`() {
        val ctx = baseContext().copy(
            hasRoute = true,
            routeTotalElevationGainM = 1500f, // > 1000m threshold
            routeSteeplyGradedPct = 15f,
        )
        val result = detector.detect(ctx, UserSettings(), workoutActive = false)
        assertEquals(RideMode.CLIMB_FOCUSED, result.mode)
        assertEquals(ModeSource.AUTO_DETECTED, result.source)
    }

    @Test
    fun `detects CLIMB_FOCUSED when 30pct of route is steep`() {
        val ctx = baseContext().copy(
            hasRoute = true,
            routeTotalElevationGainM = 400f,
            routeSteeplyGradedPct = 35f, // > 30% threshold
        )
        val result = detector.detect(ctx, UserSettings(), workoutActive = false)
        assertEquals(RideMode.CLIMB_FOCUSED, result.mode)
    }

    @Test
    fun `detects ENDURANCE for flat route`() {
        val ctx = baseContext().copy(
            hasRoute = true,
            routeTotalElevationGainM = 300f,
            routeSteeplyGradedPct = 5f,
        )
        val result = detector.detect(ctx, UserSettings(), workoutActive = false)
        assertEquals(RideMode.ENDURANCE, result.mode)
    }

    @Test
    fun `detects ADAPTIVE when no route and no workout`() {
        val ctx = baseContext().copy(hasRoute = false)
        val result = detector.detect(ctx, UserSettings(), workoutActive = false)
        assertEquals(RideMode.ADAPTIVE, result.mode)
    }

    @Test
    fun `forced mode overrides all detection`() {
        val ctx = baseContext().copy(hasRoute = true, routeTotalElevationGainM = 2000f)
        val settings = UserSettings(forcedMode = RideMode.RECOVERY)
        val result = detector.detect(ctx, settings, workoutActive = true)
        assertEquals(RideMode.RECOVERY, result.mode)
        assertEquals(ModeSource.MANUAL_OVERRIDE, result.source)
    }

    @Test
    fun `workout active takes priority over climb route`() {
        val ctx = baseContext().copy(
            hasRoute = true,
            routeTotalElevationGainM = 2000f,
        )
        val result = detector.detect(ctx, UserSettings(), workoutActive = true)
        assertEquals(RideMode.WORKOUT, result.mode)
    }

    @Test
    fun `workout active takes priority over flat route`() {
        val ctx = baseContext().copy(hasRoute = true)
        val result = detector.detect(ctx, UserSettings(), workoutActive = true)
        assertEquals(RideMode.WORKOUT, result.mode)
    }
}
