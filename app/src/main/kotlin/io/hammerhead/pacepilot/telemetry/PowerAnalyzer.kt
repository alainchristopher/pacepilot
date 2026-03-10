package io.hammerhead.pacepilot.telemetry

import io.hammerhead.pacepilot.util.RollingAverage
import io.hammerhead.pacepilot.util.ZoneCalculator
import io.hammerhead.pacepilot.util.linearSlope

/**
 * Stateful power analysis. Feed new samples via [onPowerSample].
 * All rolling windows assume 1-second samples.
 */
class PowerAnalyzer {

    // Rolling averages (1-sample = 1 second)
    private val avg5s = RollingAverage(5)
    private val avg30s = RollingAverage(30)
    private val avg3min = RollingAverage(180)

    // NP: 4th-power of 30s rolling avg
    private val np30sBuffer = mutableListOf<Int>()

    // Per-interval effort tracking for trend analysis
    private val effortSetAvgs = mutableListOf<Int>() // avg power per completed effort interval
    private var currentEffortSamples = mutableListOf<Int>()
    private var isInEffort = false

    // Zone-time tracking for current interval compliance
    private var intervalSamplesTotal = 0
    private var intervalSamplesInRange = 0

    // Sustained zone tracking for adaptive coaching
    private val recentZoneBuffer = ArrayDeque<Int>(300) // 5 min of zone samples

    fun onPowerSample(watts: Int, ftp: Int) {
        avg5s.add(watts.toDouble())
        avg30s.add(watts.toDouble())
        avg3min.add(watts.toDouble())

        val s30avg = avg30s.average.toInt()
        np30sBuffer.add(s30avg)
        if (np30sBuffer.size > 10_000) np30sBuffer.removeAt(0) // cap memory

        val zone = ZoneCalculator.powerZone(watts, ftp)
        if (recentZoneBuffer.size >= 300) recentZoneBuffer.removeFirst()
        recentZoneBuffer.addLast(zone)

        if (isInEffort) {
            currentEffortSamples.add(watts)
        }
    }

    fun onIntervalCompliance(targetLow: Int?, targetHigh: Int?) {
        intervalSamplesTotal++
        val low = targetLow ?: 0
        val high = targetHigh ?: Int.MAX_VALUE
        val cur = avg5s.average.toInt()
        if (cur in low..high) intervalSamplesInRange++
    }

    fun startEffortInterval() {
        isInEffort = true
        currentEffortSamples.clear()
        intervalSamplesTotal = 0
        intervalSamplesInRange = 0
    }

    fun endEffortInterval() {
        isInEffort = false
        if (currentEffortSamples.isNotEmpty()) {
            effortSetAvgs.add(currentEffortSamples.average().toInt())
        }
        currentEffortSamples.clear()
    }

    fun resetForNewRide() {
        avg5s.clear(); avg30s.clear(); avg3min.clear()
        np30sBuffer.clear()
        effortSetAvgs.clear(); currentEffortSamples.clear()
        recentZoneBuffer.clear()
        intervalSamplesTotal = 0; intervalSamplesInRange = 0
        isInEffort = false
    }

    // ------------------------------------------------------------------
    // Outputs
    // ------------------------------------------------------------------

    val power5sAvg: Int get() = avg5s.average.toInt()
    val power30sAvg: Int get() = avg30s.average.toInt()
    val power3minAvg: Int get() = avg3min.average.toInt()

    fun normalizedPower(): Int = ZoneCalculator.normalizedPower(np30sBuffer.takeLast(3600))

    fun variabilityIndex(): Float {
        val np = normalizedPower()
        val avg = avg3min.average.toInt()
        return if (avg <= 0) 1f else np.toFloat() / avg.toFloat()
    }

    /** 0-1 fraction of time in target range this interval */
    fun complianceScore(): Float =
        if (intervalSamplesTotal == 0) 1f
        else intervalSamplesInRange.toFloat() / intervalSamplesTotal.toFloat()

    /**
     * True if effort-set average power has been declining across sets.
     * Requires at least 3 completed efforts to detect.
     */
    fun isPowerFading(): Boolean {
        if (effortSetAvgs.size < 3) return false
        val slope = effortSetAvgs.linearSlope()
        return slope < -5.0 // dropping more than 5W per set on average
    }

    fun effortSetAverages(): List<Int> = effortSetAvgs.toList()

    /** Average zone over the last [durationSec] seconds */
    fun avgZoneLastN(durationSec: Int): Float {
        val samples = recentZoneBuffer.takeLast(durationSec)
        if (samples.isEmpty()) return 0f
        return samples.average().toFloat()
    }

    /** True if rider has been in Z1 for at least [minSec] seconds continuously */
    fun isSustainedZ1(minSec: Int): Boolean {
        val samples = recentZoneBuffer.takeLast(minSec)
        if (samples.size < minSec) return false
        return samples.all { it <= 1 }
    }
}
