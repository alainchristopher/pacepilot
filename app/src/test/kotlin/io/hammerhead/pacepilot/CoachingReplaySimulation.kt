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
            RideMode.CLIMB_FOCUSED -> ClimbCoachingRules.evaluateAll(ctx) +
                listOfNotNull(
                    EnduranceCoachingRules.fuelTimeBasedReminder(ctx),
                    EnduranceCoachingRules.drinkReminder(ctx, 20),
                )
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

    // ─── Scenario 5: Base Builder (HR) 1h — HR-zone coaching ─────────────

    @Test
    fun `base builder hr workout - 10min warmup 40min active 10min cooldown`() {
        // maxHr = 185. Zones from 5-zone model:
        // Z2 = 61-70% → 113-129 bpm (warmup/cooldown target ~70-80%: 130-148)
        // Z3 = 71-80% → 131-148 bpm (active target 80-90%: 148-166)
        // Karoo HR zones from profile (simulating custom zones):
        // Z1: 0-113, Z2: 114-130, Z3: 131-148, Z4: 149-166, Z5: 167-185
        val hrZ2lo = 114; val hrZ2hi = 130
        val hrZ3lo = 131; val hrZ3hi = 148

        val frames = mutableListOf<RideContext>()

        // 10 min warmup: target 70-80% = 130-148bpm (Z2-Z3)
        for (sec in 0..599) {
            val targetHr = (hrZ2lo + hrZ3hi) / 2  // 122bpm mid of warmup range
            val hr = when {
                sec < 120 -> 95 + (sec * 30 / 120)        // ramping up
                sec < 300 -> 120 + (sin(sec * 0.01) * 5).toInt()  // finding warmup zone
                else -> 127 + (sin(sec * 0.008) * 4).toInt()       // settled
            }
            // Power is irrelevant for HR workout — rider finds power that achieves HR
            val power = hr * 2  // rough proxy
            frames.add(baseHrWorkoutFrame(sec, hr, power, hrZ2lo, hrZ3hi,
                step = 0, totalSteps = 3, phase = IntervalPhase.WARMUP,
                elapsed = sec, remaining = 600 - sec, hrZoneBounds = listOf(0 to 113, 114 to 130, 131 to 148, 149 to 166, 167 to 185)))
        }

        // 40 min active: target 80-90% = 148-166bpm (Z3-Z4)
        for (sec in 600..2999) {
            val elapsedInBlock = sec - 600
            val hr = when {
                elapsedInBlock < 120 -> 130 + (elapsedInBlock * 20 / 120) // rising to Z3
                elapsedInBlock < 600 -> 148 + (sin(elapsedInBlock * 0.008) * 6).toInt() // Z3 floor
                elapsedInBlock < 1200 -> 152 + (sin(elapsedInBlock * 0.006) * 5).toInt() // settled Z3-Z4
                elapsedInBlock < 1800 -> 158 + (sin(elapsedInBlock * 0.005) * 4).toInt() // drifting up slightly
                else -> 162 + (sin(elapsedInBlock * 0.004) * 5).toInt() // late — hitting Z4 ceiling
            }
            val power = hr * 2
            frames.add(baseHrWorkoutFrame(sec, hr, power, 148, 166,
                step = 1, totalSteps = 3, phase = IntervalPhase.EFFORT,
                elapsed = elapsedInBlock, remaining = 2400 - elapsedInBlock,
                hrZoneBounds = listOf(0 to 113, 114 to 130, 131 to 148, 149 to 166, 167 to 185)))
        }

        // 10 min cooldown: target 70-80% = 130-148bpm
        for (sec in 3000..3599) {
            val elapsedInBlock = sec - 3000
            val hr = when {
                elapsedInBlock < 120 -> 158 - (elapsedInBlock * 20 / 120) // dropping from effort
                else -> 140 - (elapsedInBlock * 10 / 600) + (sin(elapsedInBlock * 0.01) * 4).toInt()
            }
            val power = hr * 2
            frames.add(baseHrWorkoutFrame(sec, hr.coerceAtLeast(90), power, hrZ2lo, hrZ3hi,
                step = 2, totalSteps = 3, phase = IntervalPhase.COOLDOWN,
                elapsed = elapsedInBlock, remaining = 600 - elapsedInBlock,
                hrZoneBounds = listOf(0 to 113, 114 to 130, 131 to 148, 149 to 166, 167 to 185)))
        }

        runSimulation("Base Builder HR — 10min warmup / 40min active (148-166bpm) / 10min cooldown", frames)
    }

    // ─── Scenario 7: THRESHOLD — rider starts too hard, policy enforces strict ceiling ──

    @Test
    fun `threshold workout - rider overcooks early and fades late`() {
        // 2×20 min threshold. Target: 240-260W (96-104% FTP).
        // Rider goes 275W in interval 1, then drops to 230W in interval 2.
        val sim = RideSimulator.workout(ftp = ftp, maxHr = maxHr)
            .warmupInterval(durationSec = 600)
            .effortInterval(targetLow = 240, targetHigh = 260, durationSec = 1200) {
                copy(
                    powerWatts = 275, power30sAvg = 273, power3minAvg = 270,
                    heartRateBpm = 165, cadenceRpm = 82, powerZone = 4,
                    workout = workout.copy(workoutType = WorkoutType.THRESHOLD),
                )
            }
            .recoveryInterval(durationSec = 300) {
                copy(
                    powerWatts = 115, power30sAvg = 118, heartRateBpm = 142, cadenceRpm = 80,
                    workout = workout.copy(workoutType = WorkoutType.THRESHOLD),
                )
            }
            .effortInterval(targetLow = 240, targetHigh = 260, durationSec = 1200) {
                copy(
                    powerWatts = 228, power30sAvg = 226, power3minAvg = 230,
                    heartRateBpm = 168, cadenceRpm = 79, powerZone = 4,
                    workout = workout.copy(
                        workoutType = WorkoutType.THRESHOLD,
                        powerFadingTrend = true,
                        effortAvgPowers = listOf(272, 226),
                    ),
                )
            }
            .cooldownInterval(durationSec = 300)

        runSimulation("THRESHOLD Workout — 2×20 min, overcooks early then fades", sim.build())
    }

    // ─── Scenario 8: SWEET_SPOT — steady discipline, positive feedback ────

    @Test
    fun `sweet spot workout - dialed in but one drift`() {
        // 3×12 min @ 88-93% FTP = 220-232W. Rider stays in range mostly.
        val sim = RideSimulator.workout(ftp = ftp, maxHr = maxHr)
            .warmupInterval(durationSec = 600)
            .effortInterval(targetLow = 220, targetHigh = 232, durationSec = 720) {
                copy(
                    powerWatts = 226, power30sAvg = 225, power3minAvg = 224,
                    heartRateBpm = 155, cadenceRpm = 83, powerZone = 3,
                    workout = workout.copy(workoutType = WorkoutType.SWEET_SPOT),
                )
            }
            .recoveryInterval(durationSec = 300) {
                copy(powerWatts = 110, power30sAvg = 112, heartRateBpm = 140, cadenceRpm = 78,
                    workout = workout.copy(workoutType = WorkoutType.SWEET_SPOT))
            }
            .effortInterval(targetLow = 220, targetHigh = 232, durationSec = 720) {
                copy(
                    powerWatts = 238, power30sAvg = 236, power3minAvg = 235, // drifted up!
                    heartRateBpm = 162, cadenceRpm = 84, powerZone = 3,
                    workout = workout.copy(workoutType = WorkoutType.SWEET_SPOT),
                )
            }
            .recoveryInterval(durationSec = 300) {
                copy(powerWatts = 108, power30sAvg = 110, heartRateBpm = 138, cadenceRpm = 77,
                    workout = workout.copy(workoutType = WorkoutType.SWEET_SPOT))
            }
            .effortInterval(targetLow = 220, targetHigh = 232, durationSec = 720) {
                copy(
                    powerWatts = 224, power30sAvg = 223, power3minAvg = 222,
                    heartRateBpm = 158, cadenceRpm = 82, powerZone = 3,
                    workout = workout.copy(workoutType = WorkoutType.SWEET_SPOT),
                )
            }
            .cooldownInterval(durationSec = 300)

        runSimulation("SWEET_SPOT Workout — 3×12 min, one drift above ceiling", sim.build())
    }

    // ─── Scenario 9: RECOVERY_RIDE structured — strict Z1 enforcement ─────

    @Test
    fun `recovery ride structured workout - very strict ceiling enforcement`() {
        // Z1 ceiling = 135W (54% FTP). Rider keeps drifting above.
        val sim = RideSimulator.workout(ftp = ftp, maxHr = maxHr)
            .effortInterval(targetLow = 50, targetHigh = 135, durationSec = 2400) {
                copy(
                    powerWatts = 142, power30sAvg = 140, power3minAvg = 138,
                    heartRateBpm = 118, cadenceRpm = 82, powerZone = 1,
                    workout = workout.copy(workoutType = WorkoutType.RECOVERY_RIDE),
                )
            }
            .cooldownInterval(durationSec = 300)

        runSimulation("RECOVERY_RIDE Structured — Z1 ceiling enforcement", sim.build())
    }

    private fun baseHrWorkoutFrame(
        sec: Int, hr: Int, power: Int,
        targetLow: Int, targetHigh: Int,
        step: Int, totalSteps: Int, phase: IntervalPhase,
        elapsed: Int, remaining: Int,
        hrZoneBounds: List<Pair<Int, Int>>,
    ): RideContext {
        val hrZone = hrZoneBounds.indexOfFirst { (_, max) -> hr <= max }.takeIf { it >= 0 }?.plus(1) ?: 5
        return RideContext(
            activeMode = ActiveMode(RideMode.WORKOUT, ModeSource.AUTO_DETECTED),
            isRecording = true,
            rideElapsedSec = sec.toLong(),
            ftp = ftp,
            maxHr = maxHr,
            powerWatts = power,
            power5sAvg = power,
            power30sAvg = power,
            power3minAvg = power - 5,
            variabilityIndex = 1.05f,
            powerZone = ZoneCalculator.powerZone(power, ftp),
            heartRateBpm = hr,
            hrZone = hrZone,
            hrZoneBounds = hrZoneBounds,
            cadenceRpm = 85 + (sin(sec * 0.01) * 4).toInt(),
            speedKmh = power / 10f,
            distanceKm = sec / 100f,
            workout = WorkoutState(
                isActive = true,
                currentPhase = phase,
                currentStep = step,
                totalSteps = totalSteps,
                intervalElapsedSec = elapsed,
                intervalRemainingSec = remaining,
                targetType = TargetType.HEART_RATE,
                targetLow = targetLow,
                targetHigh = targetHigh,
            )
        )
    }

    // ─── Scenario 6: Recovery ride — rider goes too hard ─────────────────

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

    // ─── Scenario 8: Real ride — March 15 2026, 40km, 675m elevation ────────
    //
    // Source: Karoo-Morning_Ride-2026-03-15-0943.fit
    // No power meter — HR-only. FTP=250 set on device. Weight ~75kg assumed.
    // Two major climbs:
    //   - Climb 1: t=87min, 10min, +127m, avg HR ~132, ~6% grade
    //   - Climb 2: t=111min, 16min, +216m, avg HR ~147, ~8-9% grade (hardest)
    // Max HR hit: 179bpm on climb 2 (t=125min, 9.5% grade)
    // Carbs: NomRide not running — all carb_burned/eaten/balance = 0 in file.
    // Mode: CLIMB_FOCUSED (675m gain, >30% route above 4% grade)
    //
    // HR zones used (maxHR=179 from file):
    //   Z1 <107, Z2 107-125, Z3 125-143, Z4 143-161, Z5 161+
    @Test
    fun `real ride march 15 - 40km 675m climb focused hr only`() {
        val rideFtp = 250
        val rideMaxHr = 179
        val rideWeight = 75f

        // HR curve derived from 2-min sampled FIT data, interpolated second-by-second
        // Segments: warmup → rolling Z2/Z3 → climb1 → descent → climb2 (hardest)
        fun hrAtSec(sec: Long): Int = when {
            sec < 300   -> 75 + (sec * 27 / 300).toInt()         // warmup to 102
            sec < 900   -> 102 + (sec - 300).toInt() * 8 / 600  // 102→110
            sec < 1800  -> 110 + (sin(sec * 0.008) * 12).toInt() + 15 // rolling Z2 ~125
            sec < 2700  -> 125 + (sin(sec * 0.006) * 8).toInt()  // 125-133 Z2/Z3
            sec < 3300  -> 130 + (sin(sec * 0.005) * 7).toInt()  // 130-137
            sec < 3600  -> 133 + (sin(sec * 0.004) * 6).toInt()  // 133-139
            sec < 4200  -> 120 + (sin(sec * 0.01) * 8).toInt()   // brief easier section ~120
            sec < 4700  -> 128 + (sin(sec * 0.006) * 7).toInt()  // 128-135
            sec < 5200  -> 133 + (sin(sec * 0.005) * 6).toInt()  // 133-139
            // Climb 1 starts ~t=5200 (87min), 10min, grade 4-7%
            sec < 5800  -> 132 + (sec - 5200).toInt() * 20 / 600  // 132→152 building
            sec < 5880  -> 152 + (sin(sec * 0.01) * 5).toInt()   // summit plateau ~152
            // Short descent + flat
            sec < 6300  -> 145 - (sec - 5880).toInt() * 30 / 420  // 145→131 recovery descent
            sec < 6500  -> 115 + (sin(sec * 0.01) * 8).toInt()   // fast descent ~115-123
            sec < 6700  -> 110 + (sin(sec * 0.008) * 7).toInt()  // 110-117
            // Climb 2 starts ~t=6700 (111min), 16min, grade 6-11% — the big one
            sec < 7000  -> 130 + (sec - 6700).toInt() * 14 / 300 // 130→144
            sec < 7300  -> 144 + (sec - 7000).toInt() * 10 / 300 // 144→154
            sec < 7600  -> 154 + (sec - 7300).toInt() * 15 / 300 // 154→169
            sec < 7700  -> 169 + (sec - 7600).toInt() * 8 / 100  // 169→177
            sec < 7800  -> 177 + (sin(sec * 0.02) * 2).toInt()   // 177-179 peak effort
            sec < 8000  -> 179 - (sec - 7800).toInt() * 20 / 200 // 179→159 summit
            // Final descent + end
            else        -> 155 - (sec - 8000).toInt() * 25 / 379 // 155→130 cooldown descent
        }.coerceIn(70, rideMaxHr)

        fun gradeAtSec(sec: Long): Float = when {
            sec < 300   -> 0.0f
            sec < 900   -> -0.8f + (sin(sec * 0.01) * 1.5).toFloat()
            sec < 1800  -> 0.5f + (sin(sec * 0.008) * 1.5).toFloat()
            sec < 2700  -> 0.7f + (sin(sec * 0.006) * 1.2).toFloat()
            sec < 3300  -> 1.5f + (sin(sec * 0.005) * 1.5).toFloat()
            sec < 4200  -> -0.5f + (sin(sec * 0.01) * 1.5).toFloat()
            sec < 4700  -> 1.5f + (sin(sec * 0.006) * 2.0).toFloat()
            sec < 5200  -> 2.0f + (sin(sec * 0.005) * 1.5).toFloat()
            // Climb 1: 4-7%
            sec < 5500  -> 5.5f + (sin(sec * 0.02) * 1.5).toFloat()
            sec < 5800  -> 6.5f + (sin(sec * 0.02) * 2.0).toFloat()
            sec < 5900  -> 1.0f
            // Descent
            sec < 6300  -> -3.5f + (sin(sec * 0.01) * 2.0).toFloat()
            sec < 6700  -> -5.5f + (sin(sec * 0.008) * 2.0).toFloat()
            // Climb 2: 6-11% sustained
            sec < 6900  -> 3.5f + (sin(sec * 0.02) * 1.5).toFloat()
            sec < 7100  -> 5.5f + (sin(sec * 0.015) * 2.0).toFloat()
            sec < 7300  -> 7.5f + (sin(sec * 0.02) * 2.0).toFloat()
            sec < 7500  -> 9.0f + (sin(sec * 0.02) * 1.5).toFloat()
            sec < 7700  -> 10.0f + (sin(sec * 0.025) * 1.5).toFloat()
            sec < 7900  -> 8.5f + (sin(sec * 0.02) * 2.0).toFloat()
            sec < 8000  -> 3.0f
            // Final descent
            else        -> -2.5f + (sin(sec * 0.01) * 2.0).toFloat()
        }

        val totalSec = 8379L
        val frames = mutableListOf<RideContext>()

        for (sec in 0..totalSec) {
            val hr = hrAtSec(sec)
            val grade = gradeAtSec(sec)
            val hrZone = ZoneCalculator.hrZone(hr, rideMaxHr)
            val isOnClimb = grade > 3.5f
            val isDescending = grade < -3.0f

            // Estimate power from HR% (no power meter — rough approximation)
            val hrPct = hr.toFloat() / rideMaxHr
            val estPower = (hrPct * hrPct * rideFtp * 1.1f).toInt().coerceIn(60, 300)
            val powerZone = ZoneCalculator.powerZone(estPower, rideFtp)

            // HR decoupling starts building after 90min
            val decoupling = when {
                sec < 5400 -> 0f
                sec < 7200 -> (sec - 5400f) / 1800f * 7f  // 0→7% on climb 2
                else -> 7f
            }

            // Carb deficit accumulates — no NomRide, no logging
            // At ~65g/hr avg rate, after 141min = ~153g target, ~0 consumed
            val elapsedHours = sec / 3600f
            val carbTarget = (elapsedHours * 65).toInt()
            val carbConsumed = 0 // no logging during this ride
            val carbDeficit = (carbTarget - carbConsumed).coerceAtLeast(0)

            // Mode: CLIMB_FOCUSED given 675m gain, multiple steep segments
            val mode = when {
                sec < 600 -> RideMode.ADAPTIVE
                else -> RideMode.CLIMB_FOCUSED
            }

            frames.add(
                RideContext(
                    activeMode = ActiveMode(mode, ModeSource.AUTO_DETECTED),
                    isRecording = true,
                    rideElapsedSec = sec,
                    ftp = rideFtp,
                    maxHr = rideMaxHr,
                    weightKg = rideWeight,
                    powerWatts = estPower,
                    power5sAvg = estPower,
                    power30sAvg = estPower,
                    power3minAvg = estPower - 5,
                    normalizedPower = (estPower * 1.04f).toInt(),
                    variabilityIndex = if (isOnClimb) 1.06f else 1.04f,
                    powerZone = powerZone,
                    heartRateBpm = hr,
                    hrZone = hrZone,
                    hrDecouplingPct = decoupling,
                    hrRecoveryRate = if (isDescending) 0.8f else 0.2f,
                    cadenceRpm = when {
                        isOnClimb && grade > 8f -> 68 + (Math.sin(sec * 0.02) * 4).toInt()
                        isOnClimb -> 75 + (Math.sin(sec * 0.02) * 5).toInt()
                        isDescending -> 85 + (Math.sin(sec * 0.015) * 8).toInt()
                        else -> 83 + (Math.sin(sec * 0.01) * 6).toInt()
                    },
                    speedKmh = when {
                        isOnClimb && grade > 8f -> 9f + (Math.sin(sec * 0.02) * 2).toFloat()
                        isOnClimb -> 13f + (Math.sin(sec * 0.02) * 3).toFloat()
                        isDescending -> 35f + (Math.sin(sec * 0.01) * 8).toFloat()
                        else -> 22f + (Math.sin(sec * 0.008) * 5).toFloat()
                    },
                    distanceKm = sec * 40.7f / totalSec,
                    elevationGradePct = grade,
                    isOnClimb = isOnClimb,
                    isDescending = isDescending,
                    hasRoute = true,
                    routeTotalElevationGainM = 675f,
                    routeSteeplyGradedPct = 35f,  // >30% of route above 4%
                    totalClimbsOnRoute = 2,
                    climbNumber = when {
                        sec < 5200 -> 0
                        sec < 6300 -> 1
                        else -> 2
                    },
                    distanceToClimbTopM = when {
                        // Approaching climb 1 at t=87min — warn at ~t=82min
                        sec in 4800..5200 -> ((5200 - sec) * 15f)  // ~6km to top, shrinks
                        // Approaching climb 2 at t=111min — warn at ~t=106min
                        sec in 6300..6700 -> ((6700 - sec) * 12f)
                        else -> null
                    },
                    carbsConsumedGrams = carbConsumed,
                    carbTargetGrams = carbTarget,
                    carbDeficitGrams = carbDeficit,
                )
            )
        }

        runSimulation(
            "REAL RIDE — March 15, 40km/675m, HR-only, no fueling logged",
            frames,
            tickIntervalSec = 10,
        )
    }
}
