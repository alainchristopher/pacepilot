package io.hammerhead.pacepilot

import io.hammerhead.pacepilot.model.WorkoutType
import io.hammerhead.pacepilot.workout.WorkoutTypeClassifier
import org.junit.Assert.*
import org.junit.Test

class WorkoutTypeClassifierTest {

    private val ftp = 250

    @Test
    fun `sweet spot classified correctly - Z3-Z4 8+ min intervals`() {
        val result = WorkoutTypeClassifier.classifyFromMetrics(
            avgTargetPct = 85, // 85% FTP = sweet spot
            effortDurationSec = 600, // 10 min
            totalIntervals = 5,
            targetLow = ftp * 83 / 100,
            targetHigh = ftp * 88 / 100,
            ftp = ftp,
        )
        assertEquals(WorkoutType.SWEET_SPOT, result)
    }

    @Test
    fun `VO2max classified correctly - Z5+ short intervals`() {
        val result = WorkoutTypeClassifier.classifyFromMetrics(
            avgTargetPct = 112, // 112% FTP = Z5
            effortDurationSec = 180, // 3 min
            totalIntervals = 9,
            targetLow = ftp * 110 / 100,
            targetHigh = ftp * 115 / 100,
            ftp = ftp,
        )
        assertEquals(WorkoutType.VO2_MAX, result)
    }

    @Test
    fun `threshold classified correctly - Z4 8-20 min`() {
        val result = WorkoutTypeClassifier.classifyFromMetrics(
            avgTargetPct = 97, // 97% FTP = threshold
            effortDurationSec = 900, // 15 min
            totalIntervals = 5,
            targetLow = ftp * 95 / 100,
            targetHigh = ftp * 100 / 100,
            ftp = ftp,
        )
        assertEquals(WorkoutType.THRESHOLD, result)
    }

    @Test
    fun `over-under classified correctly`() {
        // Range straddles threshold: 90% to 110% FTP
        val result = WorkoutTypeClassifier.classifyFromMetrics(
            avgTargetPct = 100,
            effortDurationSec = 300,
            totalIntervals = 7,
            targetLow = ftp * 90 / 100,    // 225W
            targetHigh = ftp * 110 / 100,  // 275W
            ftp = ftp,
        )
        assertEquals(WorkoutType.OVER_UNDER, result)
    }

    @Test
    fun `recovery ride classified correctly - Z1 only`() {
        val result = WorkoutTypeClassifier.classifyFromMetrics(
            avgTargetPct = 48, // below 55%
            effortDurationSec = 3600,
            totalIntervals = 1,
            targetLow = ftp * 45 / 100,
            targetHigh = ftp * 50 / 100,
            ftp = ftp,
        )
        assertEquals(WorkoutType.RECOVERY_RIDE, result)
    }

    @Test
    fun `endurance surges classified correctly - base Z2 many intervals`() {
        val result = WorkoutTypeClassifier.classifyFromMetrics(
            avgTargetPct = 65, // Z2
            effortDurationSec = 120,
            totalIntervals = 9, // many intervals
            targetLow = ftp * 60 / 100,
            targetHigh = ftp * 70 / 100,
            ftp = ftp,
        )
        assertEquals(WorkoutType.ENDURANCE_SURGES, result)
    }

    @Test
    fun `returns UNKNOWN when FTP is 0`() {
        val ws = io.hammerhead.pacepilot.model.WorkoutState(isActive = true, totalSteps = 5)
        assertEquals(WorkoutType.UNKNOWN, WorkoutTypeClassifier.classify(ws, ftp = 0))
    }
}
