package io.hammerhead.pacepilot

import io.hammerhead.pacepilot.coaching.MessageResolver
import io.hammerhead.pacepilot.coaching.OfflineMessagePools
import io.hammerhead.pacepilot.history.CueBank
import io.hammerhead.pacepilot.model.AlertStyle
import io.hammerhead.pacepilot.model.CoachingEvent
import io.hammerhead.pacepilot.model.CoachingPriority
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.RuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageResolverTest {

    @Test
    fun `uses rotating pool when no cue bank`() {
        val resolver = MessageResolver { null }
        val event = CoachingEvent(
            ruleId = RuleId.ZONE_DRIFT,
            message = "default",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.COACHING,
        )
        val ctx = RideContext(rideElapsedSec = 600)
        val msg = resolver.resolve(event, ctx)
        assertTrue(OfflineMessagePools.variants(RuleId.ZONE_DRIFT)!!.contains(msg))
    }

    @Test
    fun `prefers cue bank over pool`() {
        val bank = CueBank(
            generatedAtEpochMs = 1L,
            messages = mapOf(RuleId.ZONE_DRIFT to listOf("Custom cue from bank")),
        )
        val resolver = MessageResolver { bank }
        val event = CoachingEvent(
            ruleId = RuleId.ZONE_DRIFT,
            message = "default",
            priority = CoachingPriority.MEDIUM,
        )
        assertEquals("Custom cue from bank", resolver.resolve(event, RideContext()))
    }
}
