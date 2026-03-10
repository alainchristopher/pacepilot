package io.hammerhead.pacepilot

import io.hammerhead.pacepilot.coaching.*
import io.hammerhead.pacepilot.model.*
import io.hammerhead.pacepilot.util.ZoneCalculator
import org.junit.Test
import kotlin.math.sin

/**
 * Simulates a full ride through the coaching engine with CooldownManager,
 * printing every alert that would fire on the Karoo with timestamps.
 *
 * Run: ./gradlew :app:testDebugUnitTest --tests "io.hammerhead.pacepilot.CoachingReplaySimulation"
 */
class CoachingReplaySimulation {

    private val ftp = 250
    private val maxHr = 185

    private fun gatherCandidates(ctx: RideContext): List<CoachingEvent> =
        when (ctx.currentMode) {
            RideMode.WORKOUT -> WorkoutCoachingRules.evaluateAll(ctx, 75)
            RideMode.ENDURANCE -> EnduranceCoachingRules.evaluateAll(ctx) +
                if (ctx.isOnClimb) ClimbCoachingRules.evaluateAll(ctx) else emptyList()
            RideMode.CLIMB_FOCUSED -> ClimbCoachingRules.evaluateAll(ctx)
            RideMode.ADAPTIVE, RideMode.RECOVERY -> AdaptiveCoachingRules.evaluateAll(ctx)
        }

    private fun formatTime(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun runSimulation(
        name: String,
        frames: List<RideContext>,
        tickIntervalSec: Int = 10,
        cooldownMultiplier: Float = 1f,
    ) {
        val clockOffset = 100_000L // offset so per-rule suppression doesn't false-positive at t=0
        var simTimeSec = clockOffset
        val cooldown = CooldownManager(cooldownMultiplier) { simTimeSec }
        val alerts = mutableListOf<Triple<Long, CoachingEvent, String>>()

        println("\n${"=".repeat(72)}")
        println("  RIDE SIMULATION: $name")
        println("  FTP=$ftp  MaxHR=$maxHr  Tick=${tickIntervalSec}s  Cooldown=${cooldownMultiplier}x")
        println("${"=".repeat(72)}\n")

        for ((i, ctx) in frames.withIndex()) {
            if (i % tickIntervalSec != 0) continue

            simTimeSec = clockOffset + ctx.rideElapsedSec

            val candidates = gatherCandidates(ctx)
            if (candidates.isEmpty()) continue

            val toFire = candidates
                .sortedByDescending { it.priority.level }
                .firstOrNull { cooldown.canFire(it, ctx) }
                ?: continue

            cooldown.recordFired(toFire.ruleId, toFire.priority)

            val zone = if (ctx.ftp > 0) ZoneCalculator.powerZone(ctx.power30sAvg, ctx.ftp) else 0
            val status = "P=${ctx.power30sAvg}w Z$zone HR=${ctx.heartRateBpm} Cad=${ctx.cadenceRpm}"
            alerts.add(Triple(ctx.rideElapsedSec, toFire, status))

            val icon = when (toFire.alertStyle) {
                AlertStyle.COACHING -> "\uD83C\uDFAF"
                AlertStyle.FUEL -> "\uD83C\uDF4C"
                AlertStyle.WARNING -> "\u26A0\uFE0F"
                AlertStyle.POSITIVE -> "\u2705"
                AlertStyle.INFO -> "\u2139\uFE0F"
            }
            val title = when (toFire.alertStyle) {
                AlertStyle.COACHING -> "Coach"
                AlertStyle.FUEL -> "Fuel Up"
                AlertStyle.WARNING -> "Alert"
                AlertStyle.POSITIVE -> "Nice"
                AlertStyle.INFO -> "PacePilot"
            }
            println("  ${formatTime(ctx.rideElapsedSec)}  $icon $title: ${toFire.message}")
            println("         [$status]  rule=${toFire.ruleId}  priority=${toFire.priority}")
            println()
        }

        println("─".repeat(72))
        println("  Total alerts: ${alerts.size}")
        val byStyle = alerts.groupBy { it.second.alertStyle }
        byStyle.forEach { (style, list) -> println("    $style: ${list.size}") }
        println("─".repeat(72))
    }

    // ─── Scenario 1: Saturday 2h endurance ride ──────────────────────────

    @Test
    fun `saturday endurance ride 2h - starts too hard then settles`() {
        val frames = mutableListOf<RideContext>()
        val totalSec = 7200L

        for (sec in 0..totalSec) {
            // Z3 starts at 190w (76% of 250). Deliberate drift into Z3 at key moments.
            val power = when {
                sec < 300 -> 160 + (sec * 20 / 300).toInt()    // warm up 160→180
                sec < 600 -> 185 + (sin(sec * 0.01) * 5).toInt() // settling Z2
                sec < 1200 -> 175 + (sin(sec * 0.005) * 8).toInt() // nice Z2
                sec < 1800 -> 178 + (sin(sec * 0.003) * 6).toInt() // good Z2
                sec < 2400 -> 175 + (sin(sec * 0.003) * 5).toInt() // locked in
                sec < 3600 -> 180 + (sec - 2400).toInt() / 100  // slow drift 180→192 (into Z3!)
                sec < 4200 -> 198 + (sin(sec * 0.008) * 8).toInt() // stuck in Z3 tempo territory
                sec < 4800 -> 175 + (sin(sec * 0.01) * 10).toInt() // backed off after alert
                sec < 5400 -> 178 + (sin(sec * 0.004) * 6).toInt() // steady Z2 again
                sec < 6600 -> 172 + (sin(sec * 0.003) * 8).toInt() // late-ride fatigue
                else -> 165 + (sin(sec * 0.005) * 10).toInt()      // final 10 min, tired
            }

            val hr = when {
                sec < 300 -> 110 + (sec * 20 / 300).toInt()
                sec < 1800 -> 135 + (sin(sec * 0.008) * 8).toInt()
                sec < 3600 -> 140 + (sec - 1800).toInt() / 360  // slow HR drift
                sec < 5400 -> 148 + (sin(sec * 0.005) * 5).toInt()
                else -> 152 + (sin(sec * 0.003) * 4).toInt()     // elevated late HR
            }

            val cadence = when {
                sec < 300 -> 80 + (sec * 10 / 300).toInt()
                sec < 6000 -> 88 + (sin(sec * 0.01) * 5).toInt()
                else -> 83 + (sin(sec * 0.01) * 4).toInt()
            }

            val decoupling = when {
                sec < 3600 -> 0f
                sec < 5400 -> (sec - 3600f) / 1800f * 6f   // 0→6% over 30 min
                else -> 6f + (sec - 5400f) / 1800f * 3f     // 6→9%
            }

            val vi = when {
                sec < 900 -> 1.1f
                sec < 2400 -> 1.05f
                sec < 4200 -> 1.04f
                else -> 1.06f
            }

            val power3min = when {
                sec < 180 -> power
                else -> power + (sin(sec * 0.002) * 3).toInt()
            }

            frames.add(
                RideContext(
                    activeMode = ActiveMode(RideMode.ENDURANCE, ModeSource.AUTO_DETECTED),
                    isRecording = true,
                    rideElapsedSec = sec,
                    ftp = ftp,
                    maxHr = maxHr,
                    powerWatts = power,
                    power5sAvg = power,
                    power30sAvg = power,
                    power3minAvg = power3min,
                    normalizedPower = (power * 1.05f).toInt(),
                    variabilityIndex = vi,
                    powerZone = ZoneCalculator.powerZone(power, ftp),
                    heartRateBpm = hr,
                    hrZone = ZoneCalculator.hrZone(hr, maxHr),
                    hrDecouplingPct = decoupling,
                    hrRecoveryRate = 0.5f,
                    cadenceRpm = cadence,
                    speedKmh = power / 8f,
                    distanceKm = sec / 120f,
                )
            )
        }

        runSimulation("Saturday Endurance — 2h, drifts into Z3, late decoupling", frames)
    }

    // ─── Scenario 2: 90-min adaptive (auto-detect → endurance) ───────────

    @Test
    fun `adaptive mode ride - auto detects endurance after 10 min`() {
        val frames = mutableListOf<RideContext>()
        val totalSec = 5400L

        for (sec in 0..totalSec) {
            val power = when {
                sec < 600 -> 170 + (sec * 20 / 600).toInt()    // easy warm-up
                sec < 1800 -> 185 + (sin(sec * 0.005) * 8).toInt() // Z2 steady
                sec < 2400 -> 180 + (sin(sec * 0.004) * 6).toInt()
                sec < 3600 -> 190 + (sin(sec * 0.006) * 10).toInt() // slight push
                sec < 4800 -> 178 + (sin(sec * 0.004) * 7).toInt()
                else -> 170 + (sin(sec * 0.003) * 5).toInt()
            }

            val hr = when {
                sec < 600 -> 105 + (sec * 25 / 600).toInt()
                sec < 3600 -> 132 + (sin(sec * 0.006) * 6).toInt()
                else -> 138 + (sec - 3600).toInt() / 600
            }

            val pz = ZoneCalculator.powerZone(power, ftp)
            val vi = if (sec < 600) 1.1f else 1.05f

            frames.add(
                RideContext(
                    activeMode = ActiveMode(RideMode.ADAPTIVE, ModeSource.AUTO_DETECTED),
                    isRecording = true,
                    rideElapsedSec = sec,
                    ftp = ftp,
                    maxHr = maxHr,
                    powerWatts = power,
                    power5sAvg = power,
                    power30sAvg = power,
                    power3minAvg = power - 3,
                    normalizedPower = (power * 1.03f).toInt(),
                    variabilityIndex = vi,
                    powerZone = pz,
                    heartRateBpm = hr,
                    hrZone = ZoneCalculator.hrZone(hr, maxHr),
                    cadenceRpm = 88 + (sin(sec * 0.01) * 4).toInt(),
                    speedKmh = power / 8f,
                    distanceKm = sec / 130f,
                )
            )
        }

        runSimulation("Adaptive → Endurance — 90 min easy ride", frames)
    }

    // ─── Scenario 3: Structured workout (4×5 min VO2max) ─────────────────

    @Test
    fun `structured vo2max workout - 4x5min intervals`() {
        val sim = RideSimulator.workout(ftp = ftp, maxHr = maxHr)
            .warmupInterval(durationSec = 600)
            // Interval 1 — nails it
            .effortInterval(targetLow = 275, targetHigh = 325, durationSec = 300) {
                copy(
                    powerWatts = 300, power30sAvg = 298, power3minAvg = 295,
                    heartRateBpm = 168, cadenceRpm = 95, powerZone = 5,
                )
            }
            .recoveryInterval(durationSec = 300) {
                copy(powerWatts = 120, power30sAvg = 125, heartRateBpm = 135, cadenceRpm = 80)
            }
            // Interval 2 — goes too hard (>10% over ceiling triggers powerAboveTarget)
            .effortInterval(targetLow = 275, targetHigh = 325, durationSec = 300) {
                copy(
                    powerWatts = 365, power30sAvg = 362, power3minAvg = 358,
                    heartRateBpm = 178, cadenceRpm = 100, powerZone = 6,
                )
            }
            .recoveryInterval(durationSec = 300) {
                copy(powerWatts = 130, power30sAvg = 132, heartRateBpm = 148, cadenceRpm = 78)
            }
            // Interval 3 — fading badly (>10% below floor triggers powerBelowTarget)
            .effortInterval(targetLow = 275, targetHigh = 325, durationSec = 300) {
                copy(
                    powerWatts = 240, power30sAvg = 238, power3minAvg = 242,
                    heartRateBpm = 172, cadenceRpm = 82, powerZone = 4,
                )
            }
            .recoveryInterval(durationSec = 300) {
                copy(powerWatts = 135, power30sAvg = 138, heartRateBpm = 152, cadenceRpm = 75)
            }
            // Interval 4 — final push, last interval
            .effortInterval(targetLow = 275, targetHigh = 325, durationSec = 300) {
                copy(
                    powerWatts = 290, power30sAvg = 288, power3minAvg = 285,
                    heartRateBpm = 176, cadenceRpm = 92, powerZone = 5,
                    workout = workout.copy(
                        currentStep = workout.totalSteps - 1,
                    ),
                )
            }
            .cooldownInterval(durationSec = 300)

        runSimulation("VO2max Workout — 4×5 min @ 110-130% FTP", sim.build())
    }

    // ─── Scenario 4: Endurance ride with a climb ─────────────────────────

    @Test
    fun `endurance ride with 20 min climb in the middle`() {
        val frames = mutableListOf<RideContext>()
        val totalSec = 5400L

        for (sec in 0..totalSec) {
            val onClimb = sec in 2400..3600
            // 1200m climb, so distToTop goes from 1200→0 over 1200s
            val distToTop = if (onClimb) ((3600 - sec) * 1f).coerceAtLeast(0f) else null

            // 95% FTP = 237w. Rider needs to exceed this for climbPowerCeiling.
            val power = when {
                sec < 600 -> 160 + (sec * 15 / 600).toInt()              // warm up
                sec < 2400 -> 178 + (sin(sec * 0.005) * 8).toInt()       // flat Z2
                sec in 2400..2700 -> 248 + (sin(sec * 0.01) * 10).toInt() // climb start, way too hard!
                sec in 2700..3300 -> 215 + (sin(sec * 0.008) * 8).toInt() // settled on climb
                sec in 3300..3600 -> 240 + (sec - 3300).toInt() / 20      // summit push
                sec < 4200 -> 100 + (sin(sec * 0.01) * 20).toInt()        // descent
                else -> 178 + (sin(sec * 0.004) * 7).toInt()              // flat again
            }

            val hr = when {
                sec < 600 -> 110 + (sec * 20 / 600).toInt()
                sec < 2400 -> 135 + (sin(sec * 0.006) * 6).toInt()
                sec in 2400..3600 -> 155 + (sin(sec * 0.008) * 8).toInt() // climb HR
                sec < 4200 -> 125 + (sin(sec * 0.01) * 10).toInt()
                else -> 138 + (sin(sec * 0.005) * 5).toInt()
            }

            val cadence = when {
                sec in 2400..3600 -> 72 + (sin(sec * 0.01) * 5).toInt() // lower cadence on climb
                else -> 88 + (sin(sec * 0.01) * 4).toInt()
            }

            val grade = when {
                sec in 2400..3600 -> 6f + (sin(sec * 0.005) * 2).toFloat()
                sec in 3600..3900 -> -4f
                else -> 0.5f
            }

            frames.add(
                RideContext(
                    activeMode = ActiveMode(RideMode.ENDURANCE, ModeSource.AUTO_DETECTED),
                    isRecording = true,
                    rideElapsedSec = sec,
                    ftp = ftp,
                    maxHr = maxHr,
                    powerWatts = power,
                    power5sAvg = power,
                    power30sAvg = power,
                    power3minAvg = power - 5,
                    normalizedPower = (power * 1.04f).toInt(),
                    variabilityIndex = 1.06f,
                    powerZone = ZoneCalculator.powerZone(power, ftp),
                    heartRateBpm = hr,
                    hrZone = ZoneCalculator.hrZone(hr, maxHr),
                    cadenceRpm = cadence,
                    speedKmh = if (onClimb) 14f else 30f,
                    distanceKm = sec / 120f,
                    elevationGradePct = grade,
                    isOnClimb = onClimb,
                    isDescending = sec in 3600..3900,
                    distanceToClimbTopM = distToTop,
                    climbNumber = if (onClimb) 1 else 0,
                    totalClimbsOnRoute = 1,
                )
            )
        }

        runSimulation("Endurance + 20-min Climb — hilly route", frames)
    }

    // ─── Scenario 5: Recovery ride — rider goes too hard ─────────────────

    @Test
    fun `recovery ride - keeps going too hard`() {
        val frames = mutableListOf<RideContext>()
        val totalSec = 3600L

        for (sec in 0..totalSec) {
            // Z1 ceiling = 135w (54% of 250). Rider keeps drifting above.
            val power = when {
                sec < 300 -> 100 + (sec * 10 / 300).toInt()
                sec < 900 -> 140 + (sin(sec * 0.01) * 10).toInt()    // already above Z1 ceiling
                sec < 1200 -> 130 + (sin(sec * 0.005) * 5).toInt()   // backs off briefly
                sec < 1800 -> 150 + (sin(sec * 0.008) * 10).toInt()  // creeping Z2 again
                sec < 2400 -> 120 + (sin(sec * 0.005) * 8).toInt()   // coach worked, backed off
                sec < 3000 -> 155 + (sin(sec * 0.01) * 10).toInt()   // can't help it, Z2 again
                else -> 115 + (sin(sec * 0.006) * 8).toInt()         // finally easy for real
            }

            val hr = when {
                sec < 300 -> 95 + (sec * 15 / 300).toInt()
                sec < 1800 -> 120 + (sin(sec * 0.008) * 8).toInt()
                else -> 115 + (sin(sec * 0.006) * 6).toInt()
            }

            frames.add(
                RideContext(
                    activeMode = ActiveMode(RideMode.RECOVERY, ModeSource.MANUAL_OVERRIDE),
                    isRecording = true,
                    rideElapsedSec = sec,
                    ftp = ftp,
                    maxHr = maxHr,
                    powerWatts = power,
                    power5sAvg = power,
                    power30sAvg = power,
                    power3minAvg = power - 3,
                    variabilityIndex = 1.08f,
                    powerZone = ZoneCalculator.powerZone(power, ftp),
                    heartRateBpm = hr,
                    hrZone = ZoneCalculator.hrZone(hr, maxHr),
                    cadenceRpm = 80 + (sin(sec * 0.01) * 5).toInt(),
                    speedKmh = power / 10f,
                    distanceKm = sec / 150f,
                )
            )
        }

        runSimulation("Recovery Ride — keeps going too hard", frames)
    }
}
