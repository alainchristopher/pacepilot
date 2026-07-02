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
    )

    fun variants(ruleId: String): List<String>? = pools[ruleId]
}
