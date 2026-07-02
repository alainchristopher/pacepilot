package io.hammerhead.pacepilot

import io.hammerhead.pacepilot.coaching.RaceCoachingRules
import io.hammerhead.pacepilot.history.RacePlan
import io.hammerhead.pacepilot.model.ActiveMode
import io.hammerhead.pacepilot.model.ModeSource
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.RideMode
import io.hammerhead.pacepilot.model.RuleId
import io.hammerhead.pacepilot.settings.UserSettings
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceCoachingRulesTest {

    private val plan = RacePlan(
        enabled = true,
        targetIf = 0.82f,
        durationMin = 150,
        distanceKm = 90f,
    )

    @Test
    fun `power high fires when over race target`() {
        val ctx = RideContext(
            activeMode = ActiveMode(RideMode.RACE, ModeSource.MANUAL_OVERRIDE),
            rideElapsedSec = 3600,
            ftp = 250,
            power30sAvg = 230,
            normalizedPower = 228,
        )
        val events = RaceCoachingRules.evaluateAll(ctx, plan, UserSettings())
        assertTrue(events.any { it.ruleId == RuleId.RACE_POWER_HIGH })
    }

    @Test
    fun `finish line 75 fires at 75 percent distance`() {
        val ctx = RideContext(
            activeMode = ActiveMode(RideMode.RACE, ModeSource.MANUAL_OVERRIDE),
            rideElapsedSec = 7200,
            distanceKm = 67.5f,
            routeDistanceKm = 90f,
            ftp = 250,
        )
        val events = RaceCoachingRules.evaluateAll(ctx, plan, UserSettings())
        assertNotNull(events.find { it.ruleId == RuleId.RACE_FINISH_75 })
    }
}
