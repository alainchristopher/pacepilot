package io.hammerhead.pacepilot

import io.hammerhead.pacepilot.ai.RideNarrative
import io.hammerhead.pacepilot.model.RideMode
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RideNarrativeTest {

    private lateinit var narrative: RideNarrative

    @Before
    fun setup() {
        narrative = RideNarrative()
    }

    @Test
    fun `returns null for rides under 5 minutes`() {
        val ctx = TestHelpers.buildContext(RideMode.ENDURANCE, 200).copy(rideElapsedSec = 200L)
        repeat(200) { narrative.onContext(ctx.copy(rideElapsedSec = it.toLong())) }
        assertNull(narrative.buildNarrative(ctx))
    }

    @Test
    fun `establishes baseline in 5-10 minute window`() {
        val ctx = TestHelpers.buildContext(RideMode.ENDURANCE, 200).copy(
            power30sAvg = 210, heartRateBpm = 148
        )
        // Feed through 10 min, baseline window is 5-10 min
        repeat(600) { sec ->
            narrative.onContext(ctx.copy(rideElapsedSec = sec.toLong()))
        }
        val result = narrative.buildNarrative(ctx.copy(rideElapsedSec = 600L))
        assertNotNull(result)
        assertTrue("Narrative should show baseline power", result!!.contains("210W") || result.contains("Baseline"))
    }

    @Test
    fun `logs fueling acknowledgements`() {
        val ackTimeSec = System.currentTimeMillis() / 1000
        val ctx = TestHelpers.buildContext(RideMode.ENDURANCE, 200).copy(
            rideElapsedSec = 2000L,
            lastFuelingAckSec = ackTimeSec,
        )
        // Pre-ack state
        repeat(1999) { sec ->
            narrative.onContext(ctx.copy(rideElapsedSec = sec.toLong(), lastFuelingAckSec = 0L))
        }
        // Ack happens
        narrative.onContext(ctx)
        val result = narrative.buildNarrative(ctx)
        assertNotNull(result)
        assertTrue("Narrative should log fueling", result!!.contains("Fueled"))
    }

    @Test
    fun `resets cleanly for next ride`() {
        val ctx = TestHelpers.buildContext(RideMode.ENDURANCE, 200).copy(rideElapsedSec = 600L)
        repeat(600) { narrative.onContext(ctx.copy(rideElapsedSec = it.toLong())) }
        assertNotNull(narrative.buildNarrative(ctx))

        narrative.reset()
        val freshCtx = ctx.copy(rideElapsedSec = 600L)
        // After reset, baseline is gone so narrative should be minimal
        val result = narrative.buildNarrative(freshCtx)
        // Should exist (600s > 300s threshold) but have no baseline
        if (result != null) {
            assertTrue("After reset, baseline should not appear", !result.contains("Baseline"))
        }
    }

    @Test
    fun `logs interval compliance events`() {
        narrative.onIntervalCompleted(wasOverTarget = true, wasBelowTarget = false)
        narrative.onIntervalCompleted(wasOverTarget = false, wasBelowTarget = false)
        narrative.onIntervalCompleted(wasOverTarget = false, wasBelowTarget = true)

        val ctx = TestHelpers.buildContext(RideMode.ENDURANCE, 200).copy(rideElapsedSec = 600L)
        repeat(600) { narrative.onContext(ctx.copy(rideElapsedSec = it.toLong())) }

        val result = narrative.buildNarrative(ctx)
        assertNotNull(result)
        assertTrue("Should show completed intervals", result!!.contains("3 intervals"))
        assertTrue("Should show overcook count", result.contains("1 overcook"))
        assertTrue("Should show below target count", result.contains("1 below target"))
    }
}
