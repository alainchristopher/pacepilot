package io.hammerhead.pacepilot

import io.hammerhead.pacepilot.coaching.WorkoutTypePolicy
import io.hammerhead.pacepilot.model.WorkoutType
import org.junit.Assert.*
import org.junit.Test

class WorkoutTypePolicyTest {

    @Test
    fun `forType UNKNOWN returns DEFAULT policy`() {
        val policy = WorkoutTypePolicy.forType(WorkoutType.UNKNOWN)
        assertEquals(WorkoutTypePolicy.DEFAULT, policy)
    }

    @Test
    fun `DEFAULT policy overTargetTolerance is 10pct`() {
        assertEquals(10, WorkoutTypePolicy.DEFAULT.overTargetTolerancePct)
    }

    @Test
    fun `DEFAULT policy settleTimeSec is 30`() {
        assertEquals(30, WorkoutTypePolicy.DEFAULT.settleTimeSec)
    }

    @Test
    fun `DEFAULT positiveWindowStartSec is 55 — preserves old 55-95s check`() {
        // powerOnTarget fired at 55..95 in old code. New: positiveWindowStartSec + 40.
        assertEquals(55, WorkoutTypePolicy.DEFAULT.positiveWindowStartSec)
    }

    @Test
    fun `THRESHOLD has tighter ceiling tolerance than DEFAULT`() {
        assertTrue(WorkoutTypePolicy.THRESHOLD.overTargetTolerancePct < WorkoutTypePolicy.DEFAULT.overTargetTolerancePct)
    }

    @Test
    fun `VO2_MAX has wider ceiling tolerance than DEFAULT`() {
        assertTrue(WorkoutTypePolicy.VO2_MAX.overTargetTolerancePct > WorkoutTypePolicy.DEFAULT.overTargetTolerancePct)
    }

    @Test
    fun `RECOVERY_RIDE has smallest ceiling tolerance`() {
        val all = WorkoutType.entries.filter { it != WorkoutType.UNKNOWN }
        val minTolerance = all.minOf { WorkoutTypePolicy.forType(it).overTargetTolerancePct }
        assertEquals(minTolerance, WorkoutTypePolicy.RECOVERY_RIDE.overTargetTolerancePct)
    }

    @Test
    fun `RECOVERY_RIDE never penalises going below target`() {
        // underTargetTolerancePct of 50 means threshold is floor * 50/100 — very hard to breach
        assertTrue(WorkoutTypePolicy.RECOVERY_RIDE.underTargetTolerancePct >= 50)
    }

    @Test
    fun `VO2_MAX has longer settle time than THRESHOLD`() {
        assertTrue(WorkoutTypePolicy.VO2_MAX.settleTimeSec > WorkoutTypePolicy.THRESHOLD.settleTimeSec)
    }

    @Test
    fun `SWEET_SPOT has shorter onTargetSuppression than VO2_MAX`() {
        assertTrue(WorkoutTypePolicy.SWEET_SPOT.onTargetSuppressionSec < WorkoutTypePolicy.VO2_MAX.onTargetSuppressionSec)
    }

    @Test
    fun `OVER_UNDER has longest onTargetSuppression among common types`() {
        val common = listOf(WorkoutType.SWEET_SPOT, WorkoutType.THRESHOLD, WorkoutType.VO2_MAX, WorkoutType.OVER_UNDER)
        val max = common.maxOf { WorkoutTypePolicy.forType(it).onTargetSuppressionSec }
        assertEquals(max, WorkoutTypePolicy.OVER_UNDER.onTargetSuppressionSec)
    }

    @Test
    fun `SWEET_SPOT has earlier fadingTrendMinSets than UNKNOWN`() {
        assertTrue(WorkoutTypePolicy.SWEET_SPOT.fadingTrendMinSets < WorkoutTypePolicy.DEFAULT.fadingTrendMinSets)
    }

    @Test
    fun `all WorkoutType entries return a non-null policy`() {
        WorkoutType.entries.forEach { type ->
            assertNotNull(WorkoutTypePolicy.forType(type))
        }
    }

    @Test
    fun `aiEmphasis is non-empty for all types`() {
        WorkoutType.entries.forEach { type ->
            val emphasis = WorkoutTypePolicy.forType(type).aiEmphasis
            assertTrue("$type has empty aiEmphasis", emphasis.isNotBlank())
        }
    }
}
