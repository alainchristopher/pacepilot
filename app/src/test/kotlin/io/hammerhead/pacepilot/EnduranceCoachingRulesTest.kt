package io.hammerhead.pacepilot

import io.hammerhead.pacepilot.coaching.EnduranceCoachingRules
import io.hammerhead.pacepilot.model.RideContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnduranceCoachingRulesTest {

    @Test
    fun `drink reminder respects custom interval`() {
        val ctx = RideContext(
            rideElapsedSec = 3600,
            lastDrinkAckEpochSec = 0,
        )
        // 60 min elapsed, 45 min interval → not due yet from t=0 ack perspective... 
        // actually sinceLastDrink=3600 >= 45*60? 3600 >= 2700 yes it fires
        // use recent ack instead
        val recentAck = System.currentTimeMillis() / 1000 - 20 * 60
        val notDue = EnduranceCoachingRules.drinkReminder(
            ctx.copy(lastDrinkAckEpochSec = recentAck),
            drinkIntervalMin = 45,
        )
        assertTrue(notDue == null)

        val due = EnduranceCoachingRules.drinkReminder(
            ctx.copy(lastDrinkAckEpochSec = System.currentTimeMillis() / 1000 - 46 * 60),
            drinkIntervalMin = 45,
        )
        assertTrue(due != null)
    }

    @Test
    fun `fuel sip suffix uses drink interval not hardcoded 20 min`() {
        val now = System.currentTimeMillis() / 1000
        val base = RideContext(
            rideElapsedSec = 3600,
            lastFuelAckEpochSec = 0,
            carbDeficitGrams = 20,
        )
        // 25 min since drink — no sip at 30 min interval
        val at25 = EnduranceCoachingRules.fuelTimeBasedReminder(
            base.copy(lastDrinkAckEpochSec = now - 25 * 60),
            drinkIntervalMin = 30,
        )
        assertTrue(at25 != null)
        assertFalse(at25!!.message.contains("Sip"))

        // 35 min since drink — sip at 30 min interval
        val at35 = EnduranceCoachingRules.fuelTimeBasedReminder(
            base.copy(lastDrinkAckEpochSec = now - 35 * 60),
            drinkIntervalMin = 30,
        )
        assertTrue(at35 != null)
        assertTrue(at35!!.message.contains("Sip"))
    }
}
