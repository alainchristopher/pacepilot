package io.hammerhead.pacepilot

import io.hammerhead.pacepilot.coaching.CooldownManager
import io.hammerhead.pacepilot.model.AlertStyle
import io.hammerhead.pacepilot.model.CoachingEvent
import io.hammerhead.pacepilot.model.CoachingPriority
import io.hammerhead.pacepilot.model.IntervalPhase
import io.hammerhead.pacepilot.model.RideMode
import io.hammerhead.pacepilot.model.WorkoutState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CooldownManagerTest {

    private lateinit var manager: CooldownManager

    @Before
    fun setup() {
        manager = CooldownManager(cooldownMultiplier = 1.0f)
    }

    private fun makeEvent(
        ruleId: String = "test_rule",
        priority: CoachingPriority = CoachingPriority.MEDIUM,
        suppressSec: Int? = null,
    ) = CoachingEvent(
        ruleId = ruleId,
        message = "test",
        priority = priority,
        suppressIfFiredInLastSec = suppressSec,
    )

    @Test
    fun `canFire returns true for new event`() {
        val ctx = baseContext()
        val event = makeEvent()
        assertTrue(manager.canFire(event, ctx))
    }

    @Test
    fun `canFire returns false immediately after recording fire`() {
        val ctx = baseContext()
        val event = makeEvent()
        manager.recordFired(event.ruleId)
        // Global cooldown applies — should block since we just fired
        // (depends on system clock; in practice we can't test exact timing without mocking)
        // Instead test that per-rule suppression works with a fresh event that has suppressIfFiredInLastSec
        val suppressedEvent = makeEvent(ruleId = "test_rule", suppressSec = 300)
        manager.recordFired(suppressedEvent.ruleId)
        assertFalse(manager.canFire(suppressedEvent, ctx))
    }

    @Test
    fun `CRITICAL events bypass global cooldown`() {
        val ctx = baseContext()
        manager.recordFired("some_rule") // fire something to set global cooldown
        val criticalEvent = makeEvent(priority = CoachingPriority.CRITICAL, suppressSec = null)
        // Critical should still be allowed even right after global fire
        assertTrue(manager.canFire(criticalEvent, ctx))
    }

    @Test
    fun `globalCooldownSec is 60 during EFFORT interval in WORKOUT mode`() {
        val ctx = baseContext(mode = RideMode.WORKOUT).copy(
            workout = WorkoutState(isActive = true, currentPhase = IntervalPhase.EFFORT)
        )
        assertEquals(60L, manager.globalCooldownSec(ctx))
    }

    @Test
    fun `globalCooldownSec is 120 during RECOVERY interval in WORKOUT mode`() {
        val ctx = baseContext(mode = RideMode.WORKOUT).copy(
            workout = WorkoutState(isActive = true, currentPhase = IntervalPhase.RECOVERY)
        )
        assertEquals(120L, manager.globalCooldownSec(ctx))
    }

    @Test
    fun `globalCooldownSec is 180 during WARMUP in WORKOUT mode`() {
        val ctx = baseContext(mode = RideMode.WORKOUT).copy(
            workout = WorkoutState(isActive = true, currentPhase = IntervalPhase.WARMUP)
        )
        assertEquals(180L, manager.globalCooldownSec(ctx))
    }

    @Test
    fun `globalCooldownSec is 120 for ENDURANCE mode`() {
        val ctx = baseContext(mode = RideMode.ENDURANCE)
        assertEquals(120L, manager.globalCooldownSec(ctx))
    }

    @Test
    fun `globalCooldownSec is 120 for ADAPTIVE mode`() {
        val ctx = baseContext(mode = RideMode.ADAPTIVE)
        assertEquals(120L, manager.globalCooldownSec(ctx))
    }

    @Test
    fun `cooldown multiplier scales correctly`() {
        val doubleManager = CooldownManager(cooldownMultiplier = 2.0f)
        val ctx = baseContext(mode = RideMode.WORKOUT).copy(
            workout = WorkoutState(isActive = true, currentPhase = IntervalPhase.EFFORT)
        )
        assertEquals(120L, doubleManager.globalCooldownSec(ctx)) // 60 * 2 = 120
    }

    @Test
    fun `silence window blocks non-critical events`() {
        val nowSec = System.currentTimeMillis() / 1000
        val ctx = baseContext().copy(silencedUntilSec = nowSec + 600) // silenced for 10 min
        val event = makeEvent(priority = CoachingPriority.MEDIUM)
        assertFalse(manager.canFire(event, ctx))
    }

    @Test
    fun `silence window does NOT block CRITICAL events`() {
        val nowSec = System.currentTimeMillis() / 1000
        val ctx = baseContext().copy(silencedUntilSec = nowSec + 600)
        val event = makeEvent(priority = CoachingPriority.CRITICAL)
        assertTrue(manager.canFire(event, ctx))
    }

    @Test
    fun `reset clears all state`() {
        val ctx = baseContext()
        val event = makeEvent(suppressSec = 300)
        manager.recordFired(event.ruleId)
        manager.reset()
        assertTrue(manager.canFire(event, ctx))
    }
}
