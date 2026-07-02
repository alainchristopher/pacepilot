package io.hammerhead.pacepilot.coaching

import io.hammerhead.pacepilot.model.RuleId

/**
 * Static offline message variants per rule. [MessageResolver] picks deterministically
 * from the pool so replay tests stay stable.
 */
object OfflineMessagePools {

    private val pools: Map<String, List<String>> = mapOf(
        RuleId.ZONE_DRIFT to listOf(
            "Ease off. Back to Z2.",
            "Zone drift. Soften the legs.",
            "Too hot. Hold endurance pace.",
            "Z3 creep. Dial it back.",
        ),
        RuleId.FUEL_TIME_BASED to listOf(
            "Eat now. Stay on target.",
            "Fuel window. Take a gel.",
            "Time to eat. Protect the run.",
            "Carbs due. Eat something now.",
        ),
        RuleId.DRINK_REMINDER to listOf(
            "Take a drink.",
            "Hydrate now.",
            "Sip time. Drink up.",
            "Fluid check. Drink now.",
        ),
        RuleId.HR_DECOUPLING to listOf(
            "HR drift. Protect effort.",
            "Decoupling rising. Ease slightly.",
            "Cardiac drift. Hold steady.",
            "HR:power drift. Back off a touch.",
        ),
        RuleId.PROTECT_LAST_HOUR to listOf(
            "Last hour. Protect the run.",
            "Fatigue building. Hold steady.",
            "Late ride. Don't burn matches.",
            "Conserve now. Finish strong.",
        ),
        RuleId.PACING_CONSISTENT to listOf(
            "Smooth pacing. Well done.",
            "Steady work. Keep it up.",
            "Nice discipline. Hold this.",
            "Consistent effort. Stay locked.",
        ),
        RuleId.EARLY_RIDE_CHECK to listOf(
            "Settle in. Find your rhythm.",
            "Early miles. Stay relaxed.",
            "Easy start. Build gradually.",
            "First miles. Lock in Z2.",
        ),
        RuleId.CLIMB_POWER_CEILING to listOf(
            "Climb ceiling. Back off power.",
            "Too hard on climb. Ease 10W.",
            "Climb budget. Soften the push.",
            "Hold the climb cap.",
        ),
        RuleId.PRE_CLIMB_PREP to listOf(
            "Climb soon. Eat and drink.",
            "Approaching climb. Fuel up.",
            "Prep window. Eat now.",
        ),
        RuleId.CLIMB_ENTRY to listOf(
            "Climb started. Settle cadence.",
            "On the climb. Find rhythm.",
            "Climbing now. Stay smooth.",
        ),
        RuleId.ADAPTIVE_RECOVERY to listOf(
            "Too hard. Recovery = Z1.",
            "Ease off. True recovery pace.",
            "Above Z1. Soften now.",
        ),
        RuleId.FUEL_FIRST_30MIN to listOf(
            "30min in. Time to fuel.",
            "Half hour. Eat something.",
            "Fuel check. Take carbs now.",
        ),
        RuleId.POWER_ABOVE_TARGET to listOf(
            "Too hard. Back off now.",
            "Over target. Ease power.",
            "Above ceiling. Pull back.",
        ),
        RuleId.POWER_BELOW_TARGET to listOf(
            "Under target. Push a bit.",
            "More watts needed here.",
            "Below floor. Add pressure.",
        ),
        RuleId.RECOVERY_NOT_RECOVERING to listOf(
            "Not recovering. Ease off.",
            "Recovery too hot. Soften.",
            "HR still high. Back off.",
        ),
        RuleId.SESSION_COMPLETE to listOf(
            "Session done. Great work.",
            "Workout complete. Nice job.",
            "All intervals done. Recover well.",
        ),
        // Race mode
        RuleId.RACE_POWER_HIGH to listOf(
            "Too hot. Protect the run.",
            "Above race watts. Ease now.",
            "Burning matches. Back off.",
            "Over plan. Save it for T2.",
        ),
        RuleId.RACE_POWER_LOW to listOf(
            "Below race watts. Hold plan.",
            "Too easy. Lift to target.",
            "Under target. Stay honest.",
        ),
        RuleId.RACE_VI_HIGH to listOf(
            "Surges costly. Steady power.",
            "High variability. Smooth out.",
            "Stop spiking. Hold watts.",
        ),
        RuleId.RACE_FUEL to listOf(
            "Race fuel window. Eat now.",
            "Carbs on schedule. Eat.",
            "Fuel now. Gut still open.",
        ),
        RuleId.RACE_DRINK to listOf(
            "Race drink stop. Sip now.",
            "Hydrate on plan.",
            "Fluid on schedule.",
        ),
        RuleId.RACE_FINISH_75 to listOf(
            "75% done. Stay patient.",
            "Three-quarters. Hold plan.",
            "Keep executing. No heroics.",
        ),
        RuleId.RACE_FINISH_90 to listOf(
            "Final stretch. Hold form.",
            "90% done. Finish smart.",
            "Almost there. Stay steady.",
        ),
        RuleId.RACE_NEGATIVE_SPLIT to listOf(
            "Early miles. Hold under plan.",
            "Negative split. Bank time now.",
            "First 40km easy. Trust plan.",
        ),
        RuleId.RACE_T2_PREP to listOf(
            "T2 soon. Stop fueling hard.",
            "Final 20min. Light on gut.",
            "Run prep. Ease fuel load.",
        ),
        // Workout mode
        RuleId.PRE_INTERVAL_ALERT to listOf(
            "Effort coming. Get ready.",
            "Hard block soon. Prepare.",
            "Interval ahead. Settle in.",
        ),
        RuleId.PRE_INTERVAL_FUELING to listOf(
            "Fuel now. Effort coming.",
            "Eat before the block.",
            "Gel time. Hard effort next.",
        ),
        RuleId.FIRST_INTERVAL to listOf(
            "First block. Don't overcook.",
            "Interval one. Find rhythm.",
            "Settle in. Hold target.",
        ),
        RuleId.POWER_ON_TARGET to listOf(
            "Good. Hold.",
            "On target. Stay locked.",
            "Perfect watts. Keep it.",
        ),
        RuleId.INTERVAL_COUNTDOWN to listOf(
            "30 sec left. Hold.",
            "Final push. Stay strong.",
            "Almost done. Commit.",
        ),
        RuleId.CADENCE_DROPPING to listOf(
            "Spin lighter. Cadence up.",
            "Legs heavy. Lift cadence.",
            "Cadence drop. Spin faster.",
        ),
        RuleId.HR_CEILING_EXCEEDED to listOf(
            "HR too high. Back off.",
            "Over ceiling. Ease power.",
            "Heart rate hot. Soften.",
        ),
        RuleId.HR_BELOW_TARGET to listOf(
            "HR low. Push a touch.",
            "Below HR target. Lift.",
            "More effort needed.",
        ),
        RuleId.HR_ON_TARGET to listOf(
            "HR on target. Hold.",
            "Good HR. Stay steady.",
            "Heart rate locked.",
        ),
        RuleId.RECOVERY_FUELING_WINDOW to listOf(
            "Recovery window. Eat now.",
            "Fuel between efforts.",
            "Good time for a gel.",
        ),
        RuleId.LAST_INTERVAL_MOTIVATION to listOf(
            "Final block. You have this.",
            "Last rep. Empty the tank.",
            "Finish strong. One more.",
        ),
        RuleId.POWER_FADING_TREND to listOf(
            "Power fading. Consider stopping.",
            "Output dropping. Protect form.",
            "Fatigue showing. Ease off.",
        ),
        RuleId.RECOVERY_QUALITY_DECLINING to listOf(
            "Recovery slowing. Cut reps.",
            "Not recovering well. Adjust.",
            "HR not dropping. Stop soon.",
        ),
        RuleId.HR_NOT_DROPPING to listOf(
            "HR still high. Spin easy.",
            "Not recovering. Ease off.",
            "Heart rate stuck. Soften.",
        ),
        RuleId.RECOVERING_WELL to listOf(
            "Recovering well. Nice.",
            "Good recovery. Ready soon.",
            "HR dropping. Well done.",
        ),
        // Climb mode
        RuleId.CLIMB_BUDGET to listOf(
            "Climb budget. Pace it.",
            "Save matches for summit.",
            "Climb effort. Stay measured.",
        ),
        RuleId.CLIMB_CADENCE_DROP to listOf(
            "Cadence drop on climb. Spin.",
            "Grinding. Lift cadence.",
            "Climb cadence low. Spin up.",
        ),
        RuleId.CLIMB_SUMMIT_NEAR to listOf(
            "Summit near. Hold steady.",
            "Top coming. Stay smooth.",
            "Almost over. Push through.",
        ),
        RuleId.CLIMB_DESCENT to listOf(
            "Descent. Eat and drink.",
            "Downhill. Refuel now.",
            "Recovery descent. Fuel up.",
        ),
        RuleId.MULTI_CLIMB_FATIGUE to listOf(
            "Multiple climbs. Conserve.",
            "Climb fatigue. Ease power.",
            "Another climb. Stay patient.",
        ),
        // Adaptive info
        RuleId.ADAPTIVE_OBSERVING to listOf(
            "Reading ride. Coach starting.",
            "Observing. Coach soon.",
            "Learning your ride.",
        ),
        RuleId.ADAPTIVE_ENDURANCE to listOf(
            "Endurance ride. Coaching on.",
            "Steady day. Coach active.",
            "Endurance mode. Stay Z2.",
        ),
        RuleId.ADAPTIVE_UNSTRUCTURED to listOf(
            "Free ride. Fuel coach on.",
            "Mixed ride. Fuel reminders on.",
            "Unstructured. Fueling active.",
        ),
    )

    fun variants(ruleId: String): List<String>? = pools[ruleId]
}
