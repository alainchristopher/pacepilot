package io.hammerhead.pacepilot.fit

import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.WriteToRecordMesg
import io.hammerhead.karooext.models.WriteToSessionMesg
import io.hammerhead.pacepilot.model.CoachingEvent
import io.hammerhead.pacepilot.model.CoachingPriority
import io.hammerhead.pacepilot.model.RideMode
import timber.log.Timber

/**
 * Emits coaching telemetry as FIT developer fields.
 *
 * Record message (event-time):
 * - pp_rule_num
 * - pp_mode
 * - pp_priority
 * - pp_msg_len
 *
 * Session message (ride-end):
 * - pp_total_alerts
 */
class CoachingFitExporter {
    private var emitter: Emitter<FitEffect>? = null
    private var totalAlerts: Int = 0

    // FIT base type id 136 = float32 (we write numeric values through FieldValue(Double)).
    private val devRuleNum = DeveloperField(0, 136, "pp_rule_num", "id")
    private val devMode = DeveloperField(1, 136, "pp_mode", "enum")
    private val devPriority = DeveloperField(2, 136, "pp_priority", "enum")
    private val devMsgLen = DeveloperField(3, 136, "pp_msg_len", "chars")
    private val devTotalAlerts = DeveloperField(4, 136, "pp_total_alerts", "count")

    fun attach(emitter: Emitter<FitEffect>) {
        this.emitter = emitter
        totalAlerts = 0
        Timber.i("CoachingFitExporter: attached")
    }

    fun detach() {
        this.emitter = null
    }

    fun onCoachingEvent(event: CoachingEvent, message: String, mode: RideMode) {
        val currentEmitter = emitter ?: return
        totalAlerts += 1
        runCatching {
            currentEmitter.onNext(
                WriteToRecordMesg(
                    listOf(
                        FieldValue(devRuleNum, stableRuleNumber(event.ruleId).toDouble()),
                        FieldValue(devMode, modeToNumber(mode).toDouble()),
                        FieldValue(devPriority, priorityToNumber(event.priority).toDouble()),
                        FieldValue(devMsgLen, message.length.toDouble()),
                    )
                )
            )
        }.onFailure { Timber.w(it, "CoachingFitExporter: failed to write record field") }
    }

    fun onRideEnd() {
        val currentEmitter = emitter ?: return
        runCatching {
            currentEmitter.onNext(
                WriteToSessionMesg(
                    FieldValue(devTotalAlerts, totalAlerts.toDouble())
                )
            )
        }.onFailure { Timber.w(it, "CoachingFitExporter: failed to write session field") }
    }

    private fun stableRuleNumber(ruleId: String): Int =
        // Keep deterministic and compact enough for charting.
        (ruleId.hashCode() and 0x7FFFFFFF) % 10_000

    private fun priorityToNumber(priority: CoachingPriority): Int = when (priority) {
        CoachingPriority.CRITICAL -> 5
        CoachingPriority.HIGH -> 4
        CoachingPriority.MEDIUM -> 3
        CoachingPriority.LOW -> 2
        CoachingPriority.INFO -> 1
    }

    private fun modeToNumber(mode: RideMode): Int = when (mode) {
        RideMode.WORKOUT -> 1
        RideMode.ENDURANCE -> 2
        RideMode.CLIMB_FOCUSED -> 3
        RideMode.ADAPTIVE -> 4
        RideMode.RECOVERY -> 5
    }
}

