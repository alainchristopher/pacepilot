package io.hammerhead.pacepilot.telemetry

import io.hammerhead.pacepilot.util.RollingAverage
import io.hammerhead.pacepilot.util.ZoneCalculator

/**
 * Stateful HR analysis. Feed samples via [onHrSample].
 * Tracks recovery drop rate between effort intervals and HR:power decoupling.
 */
class HrAnalyzer {

    private val avg60s = RollingAverage(60)
    private val avg5min = RollingAverage(300)

    // Recovery quality tracking
    private val recoveryDropRates = mutableListOf<Float>()  // bpm/sec, per recovery period
    private var recoveryStartBpm: Int = 0
    private var recoveryElapsedSec: Int = 0
    private var isInRecovery = false

    // Decoupling: track HR/power ratio over time
    private val hrPowerRatioEarlyBuffer = mutableListOf<Float>()  // first half of ride
    private val hrPowerRatioLateBuffer = mutableListOf<Float>()   // second half
    private var rideElapsedSec: Long = 0L
    private var expectedRideDurationSec: Long = 7200L // default 2h; update from RideContext

    // Zone-time tracking
    private val recentZoneBuffer = ArrayDeque<Int>(120) // 2 min

    fun onHrSample(bpm: Int, powerWatts: Int, rideElapsed: Long, maxHr: Int) {
        avg60s.add(bpm.toDouble())
        avg5min.add(bpm.toDouble())
        rideElapsedSec = rideElapsed

        val zone = ZoneCalculator.hrZone(bpm, maxHr)
        if (recentZoneBuffer.size >= 120) recentZoneBuffer.removeFirst()
        recentZoneBuffer.addLast(zone)

        // HR:power decoupling tracking
        if (powerWatts > 0 && bpm > 0) {
            val ratio = bpm.toFloat() / powerWatts.toFloat()
            val halfPoint = expectedRideDurationSec / 2
            if (rideElapsed < halfPoint) {
                if (hrPowerRatioEarlyBuffer.size < 300) hrPowerRatioEarlyBuffer.add(ratio)
            } else {
                if (hrPowerRatioLateBuffer.size < 300) hrPowerRatioLateBuffer.add(ratio)
            }
        }

        // Recovery tracking
        if (isInRecovery) {
            recoveryElapsedSec++
            if (recoveryElapsedSec == 60 && recoveryStartBpm > 0) {
                val currentBpm = avg60s.average.toInt()
                val dropBpm = recoveryStartBpm - currentBpm
                val rate = dropBpm.toFloat() / 60f // bpm per second
                recoveryDropRates.add(rate)
            }
        }
    }

    fun startRecovery(currentBpm: Int) {
        isInRecovery = true
        recoveryStartBpm = currentBpm
        recoveryElapsedSec = 0
    }

    fun endRecovery() {
        isInRecovery = false
        recoveryStartBpm = 0
        recoveryElapsedSec = 0
    }

    fun resetForNewRide() {
        avg60s.clear(); avg5min.clear()
        recoveryDropRates.clear()
        hrPowerRatioEarlyBuffer.clear()
        hrPowerRatioLateBuffer.clear()
        recentZoneBuffer.clear()
        recoveryStartBpm = 0; recoveryElapsedSec = 0; isInRecovery = false
        rideElapsedSec = 0L
    }

    // ------------------------------------------------------------------
    // Outputs
    // ------------------------------------------------------------------

    val hr60sAvg: Int get() = avg60s.average.toInt()
    val hr5minAvg: Int get() = avg5min.average.toInt()

    /**
     * Current recovery quality: most recent HR drop rate (bpm/sec).
     * Higher = recovering faster. 0 if no recovery tracked yet.
     */
    fun lastRecoveryDropRate(): Float = recoveryDropRates.lastOrNull() ?: 0f

    /**
     * True if recovery quality is declining across sets.
     * Detects when HR drop rate is slowing (lower values in recent sets).
     */
    fun isRecoveryQualityDeclining(): Boolean {
        if (recoveryDropRates.size < 2) return false
        val recent = recoveryDropRates.takeLast(3)
        // Slope should be negative (drop rate decreasing = worse recovery)
        val first = recent.first()
        val last = recent.last()
        return last < first * 0.7f // 30% slower recovery
    }

    /**
     * HR:power decoupling percentage.
     * Compares average HR/power ratio in first vs second half of ride.
     * Positive = HR drifting up relative to power (cardiac drift).
     */
    fun decouplingPct(): Float {
        if (hrPowerRatioEarlyBuffer.isEmpty() || hrPowerRatioLateBuffer.isEmpty()) return 0f
        val early = hrPowerRatioEarlyBuffer.average().toFloat()
        if (early <= 0f) return 0f
        val late = hrPowerRatioLateBuffer.average().toFloat()
        return ((late - early) / early) * 100f
    }

    /**
     * Seconds elapsed in current recovery with HR still elevated above [thresholdBpm].
     */
    fun recoveryElapsedWithElevatedHr(thresholdBpm: Int): Int {
        if (!isInRecovery) return 0
        return if (avg60s.average.toInt() > thresholdBpm) recoveryElapsedSec else 0
    }

    /** Average HR zone over the last [durationSec] seconds */
    fun avgZoneLastN(durationSec: Int): Float {
        val samples = recentZoneBuffer.takeLast(durationSec)
        if (samples.isEmpty()) return 0f
        return samples.average().toFloat()
    }

    val recoveryDropRateHistory: List<Float> get() = recoveryDropRates.toList()

    /** Average recovery drop rate across all tracked recoveries this ride */
    fun avgRecoveryRate(): Float =
        if (recoveryDropRates.isEmpty()) 0f
        else recoveryDropRates.average().toFloat()
}
