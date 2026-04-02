package io.hammerhead.pacepilot.telemetry

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnNavigationState.NavigationState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.pacepilot.model.ActiveMode
import io.hammerhead.pacepilot.model.IntervalPhase
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.coaching.FuelingIntelligence
import io.hammerhead.pacepilot.integrations.NomRideAdapter
import io.hammerhead.pacepilot.integrations.NomRideSignal
import io.hammerhead.pacepilot.integrations.SevenClimbAdapter
import io.hammerhead.pacepilot.integrations.SevenClimbSignal
import io.hammerhead.pacepilot.integrations.HeadwindAdapter
import io.hammerhead.pacepilot.integrations.HeadwindSignal
import io.hammerhead.pacepilot.util.ZoneCalculator
import io.hammerhead.pacepilot.settings.SettingsRepository
import io.hammerhead.pacepilot.workout.WorkoutTracker
import io.hammerhead.pacepilot.util.consumerFlow
import io.hammerhead.pacepilot.util.streamDataFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Combines all Karoo data streams into a single [RideContext] StateFlow.
 * Instantiate once; call [start]/[stop] with ride lifecycle.
 */
class TelemetryAggregator(
    private val karooSystem: KarooSystemService,
    private val workoutCollector: WorkoutStreamCollector,
    private val workoutTracker: WorkoutTracker,
    private val settingsRepo: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _context = MutableStateFlow(RideContext())
    val rideContext: StateFlow<RideContext> = _context.asStateFlow()

    val powerAnalyzer = PowerAnalyzer()
    val hrAnalyzer = HrAnalyzer()

    private var ftpFromProfile = 250
    private var maxHrFromProfile = 185
    private var rideStartEpochSec = 0L
    private var lastTickSec = 0L
    private val nomRideAdapter = NomRideAdapter(karooSystem, scope)
    @Volatile private var nomRideSignal: NomRideSignal? = null
    private val sevenClimbAdapter = SevenClimbAdapter(karooSystem, scope)
    @Volatile private var sevenClimbSignal: SevenClimbSignal? = null
    private val headwindAdapter = HeadwindAdapter(karooSystem, scope)
    @Volatile private var headwindSignal: HeadwindSignal? = null

    private val streamJobs = mutableListOf<Job>()

    fun start() {
        rideStartEpochSec = System.currentTimeMillis() / 1000
        workoutCollector.start()

        streamJobs += scope.launch { collectUserProfile() }
        streamJobs += scope.launch { collectPower() }
        streamJobs += scope.launch { collectHr() }
        streamJobs += scope.launch { collectCadence() }
        streamJobs += scope.launch { collectSpeed() }
        streamJobs += scope.launch { collectDistance() }
        streamJobs += scope.launch { collectGrade() }
        streamJobs += scope.launch { collectElevationGain() }
        streamJobs += scope.launch { collectNavigation() }
        collectClimbStreams() // adds to streamJobs internally
        streamJobs += scope.launch { collectWorkoutState() }
        streamJobs += scope.launch { tickLoop() }
        nomRideAdapter.start { signal ->
            val existing = nomRideSignal
            nomRideSignal = NomRideSignal(
                carbBalanceGrams = signal.carbBalanceGrams ?: existing?.carbBalanceGrams,
                burnRateGph = signal.burnRateGph ?: existing?.burnRateGph,
                carbsEatenGrams = signal.carbsEatenGrams ?: existing?.carbsEatenGrams,
                waterMl = signal.waterMl ?: existing?.waterMl,
                updatedAtEpochSec = signal.updatedAtEpochSec,
            )
        }
        sevenClimbAdapter.start { signal ->
            val existing = sevenClimbSignal
            sevenClimbSignal = SevenClimbSignal(
                distanceToTopM = signal.distanceToTopM ?: existing?.distanceToTopM,
                climbNumber = signal.climbNumber ?: existing?.climbNumber,
                totalClimbs = signal.totalClimbs ?: existing?.totalClimbs,
                gradePct = signal.gradePct ?: existing?.gradePct,
                updatedAtEpochSec = signal.updatedAtEpochSec,
            )
            // Apply overlays immediately; native streams remain fallback.
            if (signal.distanceToTopM != null) {
                _context.update { it.copy(distanceToClimbTopM = signal.distanceToTopM) }
            }
            if (signal.climbNumber != null || signal.totalClimbs != null) {
                _context.update {
                    it.copy(
                        climbNumber = signal.climbNumber ?: it.climbNumber,
                        totalClimbsOnRoute = signal.totalClimbs ?: it.totalClimbsOnRoute,
                    )
                }
            }
            if (signal.gradePct != null) {
                _context.update {
                    it.copy(
                        elevationGradePct = signal.gradePct,
                        isOnClimb = signal.gradePct > 1.5f,
                        isDescending = signal.gradePct < -1.5f,
                    )
                }
            }
        }
        headwindAdapter.start { signal ->
            val existing = headwindSignal
            headwindSignal = HeadwindSignal(
                windSpeedKmh = signal.windSpeedKmh ?: existing?.windSpeedKmh,
                relativeWindPct = signal.relativeWindPct ?: existing?.relativeWindPct,
                updatedAtEpochSec = signal.updatedAtEpochSec,
            )
            _context.update {
                it.copy(
                    windSpeedKmh = signal.windSpeedKmh ?: it.windSpeedKmh,
                    relativeWindPct = signal.relativeWindPct ?: it.relativeWindPct,
                )
            }
        }
    }

    fun stop() {
        streamJobs.forEach { it.cancel() }
        streamJobs.clear()
        nomRideAdapter.stop()
        sevenClimbAdapter.stop()
        headwindAdapter.stop()
        nomRideSignal = null
        sevenClimbSignal = null
        headwindSignal = null
        powerAnalyzer.resetForNewRide()
        hrAnalyzer.resetForNewRide()
    }

    fun updateMode(mode: ActiveMode) {
        _context.update { it.copy(activeMode = mode) }
    }

    fun updateSilence(untilSec: Long) {
        _context.update { it.copy(silencedUntilSec = untilSec) }
    }

    fun acknowledgedEat(gramsPerServing: Int) {
        val nowSec = System.currentTimeMillis() / 1000
        _context.update { c ->
            val newTotal = c.carbsConsumedGrams + gramsPerServing
            c.copy(
                carbsConsumedGrams = newTotal,
                carbDeficitGrams = (c.carbTargetGrams - newTotal).coerceAtLeast(0),
                lastFuelAckEpochSec = nowSec,
                fuelAckCount = c.fuelAckCount + 1,
            )
        }
    }

    fun acknowledgedDrink() {
        val nowSec = System.currentTimeMillis() / 1000
        _context.update { c ->
            c.copy(
                lastDrinkAckEpochSec = nowSec,
                drinkAckCount = c.drinkAckCount + 1,
            )
        }
    }

    // ------------------------------------------------------------------
    // Private stream collectors
    // ------------------------------------------------------------------

    private suspend fun collectUserProfile() {
        karooSystem.consumerFlow<UserProfile>().collect { profile ->
            val settings = settingsRepo.current
            ftpFromProfile = profile.ftp
            maxHrFromProfile = profile.maxHr
            val ftp = if (settings.ftpOverride > 0) settings.ftpOverride else ftpFromProfile
            val maxHr = if (settings.maxHrOverride > 0) settings.maxHrOverride else maxHrFromProfile

            // Use Karoo's actual HR zone bounds (e.g. if user has custom zones set)
            val hrZoneBounds = profile.heartRateZones
                .map { zone -> zone.min to zone.max }

            // Use Karoo's actual power zone bounds if configured
            val powerZoneBounds = profile.powerZones
                .map { zone -> zone.min to zone.max }

            // Weight in kg (Karoo stores in kg regardless of preferred unit display)
            val weightKg = profile.weight.takeIf { it > 0f } ?: 75f

            Timber.d("UserProfile: ftp=$ftp maxHr=$maxHr weight=${weightKg}kg hrZones=${hrZoneBounds.size} powerZones=${powerZoneBounds.size}")
            _context.update {
                it.copy(
                    ftp = ftp,
                    maxHr = maxHr,
                    hrZoneBounds = hrZoneBounds,
                    powerZoneBounds = powerZoneBounds,
                    weightKg = weightKg,
                )
            }
        }
    }

    private suspend fun collectPower() {
        karooSystem.streamDataFlow(DataType.Type.POWER)
            .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue?.toInt() }
            .collect { watts ->
                val ctx = _context.value
                powerAnalyzer.onPowerSample(watts, ctx.ftp)
                if (ctx.workout.isActive) {
                    powerAnalyzer.onIntervalCompliance(ctx.workout.targetLow, ctx.workout.targetHigh)
                }
                val zone = ZoneCalculator.powerZone(watts, ctx.ftp)
                _context.update { c ->
                    c.copy(
                        powerWatts = watts,
                        power5sAvg = powerAnalyzer.power5sAvg,
                        power30sAvg = powerAnalyzer.power30sAvg,
                        power3minAvg = powerAnalyzer.power3minAvg,
                        normalizedPower = powerAnalyzer.normalizedPower(),
                        variabilityIndex = powerAnalyzer.variabilityIndex(),
                        powerZone = zone,
                    )
                }
            }
    }

    private suspend fun collectHr() {
        karooSystem.streamDataFlow(DataType.Type.HEART_RATE)
            .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue?.toInt() }
            .collect { bpm ->
                val ctx = _context.value
                hrAnalyzer.onHrSample(bpm, ctx.powerWatts, ctx.rideElapsedSec, ctx.maxHr)
                // Use Karoo profile zones if available, otherwise fall back to % maxHr
                val zone = if (ctx.hrZoneBounds.isNotEmpty()) {
                    ctx.hrZoneBounds.indexOfFirst { (_, max) -> bpm <= max }
                        .takeIf { it >= 0 }?.plus(1)
                        ?: ctx.hrZoneBounds.size  // above all zones = highest zone
                } else {
                    ZoneCalculator.hrZone(bpm, ctx.maxHr)
                }
                _context.update { c ->
                    c.copy(
                        heartRateBpm = bpm,
                        hrZone = zone,
                        hrRecoveryRate = hrAnalyzer.lastRecoveryDropRate(),
                        hrDecouplingPct = hrAnalyzer.decouplingPct(),
                    )
                }
            }
    }

    private suspend fun collectCadence() {
        karooSystem.streamDataFlow(DataType.Type.CADENCE)
            .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue?.toInt() }
            .collect { rpm -> _context.update { it.copy(cadenceRpm = rpm) } }
    }

    private suspend fun collectSpeed() {
        karooSystem.streamDataFlow(DataType.Type.SPEED)
            .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue }
            .collect { mps -> _context.update { it.copy(speedKmh = (mps * 3.6).toFloat()) } }
    }

    private suspend fun collectDistance() {
        karooSystem.streamDataFlow(DataType.Type.DISTANCE)
            .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue }
            .collect { m -> _context.update { it.copy(distanceKm = (m / 1000.0).toFloat()) } }
    }

    private suspend fun collectGrade() {
        karooSystem.streamDataFlow(DataType.Type.ELEVATION_GRADE)
            .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue }
            .collect { grade ->
                _context.update { c ->
                    c.copy(
                        elevationGradePct = grade.toFloat(),
                        isOnClimb = grade > 1.5,
                        isDescending = grade < -1.5,
                    )
                }
            }
    }

    private suspend fun collectElevationGain() {
        karooSystem.streamDataFlow(DataType.Type.ELEVATION_GAIN)
            .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue }
            .collect { gainM -> _context.update { it.copy(elevationGainM = gainM.toFloat()) } }
    }

    private suspend fun collectNavigation() {
        karooSystem.consumerFlow<OnNavigationState>().collect { navState ->
            runCatching {
                when (val nav = navState.state) {
                    is NavigationState.NavigatingRoute -> {
                        _context.update { c ->
                            c.copy(
                                hasRoute = true,
                                totalClimbsOnRoute = runCatching { nav.climbs.size }.getOrElse { 0 },
                                routeTotalElevationGainM = runCatching {
                                    nav.climbs.sumOf { it.totalElevation }.toFloat()
                                }.getOrElse { 0f },
                                routeSteeplyGradedPct = runCatching {
                                    val dist = nav.routeDistance.toFloat()
                                    if (dist > 0)
                                        nav.climbs.filter { it.grade > 4.0 }
                                            .sumOf { it.length }
                                            .toFloat() / dist * 100f
                                    else 0f
                                }.getOrElse { 0f },
                            )
                        }
                    }
                    is NavigationState.NavigatingToDestination -> {
                        _context.update { c ->
                            c.copy(
                                hasRoute = true,
                                totalClimbsOnRoute = runCatching { nav.climbs.size }.getOrElse { 0 },
                                routeTotalElevationGainM = runCatching {
                                    nav.climbs.sumOf { it.totalElevation }.toFloat()
                                }.getOrElse { 0f },
                            )
                        }
                    }
                    is NavigationState.Idle -> {
                        _context.update { c ->
                            c.copy(
                                hasRoute = false,
                                routeTotalElevationGainM = 0f,
                                routeSteeplyGradedPct = 0f,
                                totalClimbsOnRoute = 0,
                            )
                        }
                    }
                }
            }.onFailure { Timber.w(it, "TelemetryAggregator: navigation parse error (non-fatal)") }
        }
    }

    private fun collectClimbStreams() {
        // DISTANCE_TO_TOP stream
        streamJobs += scope.launch {
            karooSystem.streamDataFlow(DataType.Type.DISTANCE_TO_TOP)
                .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue?.toFloat() }
                .collect { distM -> _context.update { it.copy(distanceToClimbTopM = distM) } }
        }
        // CLIMB_NUMBER stream: provides current climb index + total via values map
        streamJobs += scope.launch {
            karooSystem.streamDataFlow(DataType.Type.CLIMB_NUMBER)
                .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.values }
                .collect { values ->
                    runCatching {
                        val climbNum = values["CLIMB_NUMBER"]?.toInt()
                            ?: values.values.firstOrNull()?.toInt() ?: 0
                        val totalClimbs = values["TOTAL_CLIMBS"]?.toInt()
                            ?: values.values.drop(1).firstOrNull()?.toInt()
                            ?: _context.value.totalClimbsOnRoute
                        _context.update { it.copy(climbNumber = climbNum, totalClimbsOnRoute = totalClimbs) }
                    }.onFailure { Timber.w(it, "TelemetryAggregator: CLIMB_NUMBER parse error") }
                }
        }
    }

    private suspend fun collectWorkoutState() {
        workoutCollector.state.collect { rawWs ->
            val ctx = _context.value
            // Enrich WorkoutState with sticky type classification (WorkoutTracker is stateful)
            val ws = workoutTracker.update(rawWs, ctx.ftp)

            // Notify analyzers of interval phase transitions
            val prevPhase = ctx.workout.currentPhase
            if (ws.currentPhase != prevPhase) {
                when (ws.currentPhase) {
                    IntervalPhase.EFFORT -> {
                        powerAnalyzer.startEffortInterval()
                    }
                    IntervalPhase.RECOVERY -> {
                        powerAnalyzer.endEffortInterval()
                        hrAnalyzer.startRecovery(ctx.heartRateBpm)
                    }
                    else -> {
                        if (prevPhase == IntervalPhase.RECOVERY) {
                            hrAnalyzer.endRecovery()
                        }
                    }
                }
            }

            val completedEfforts = powerAnalyzer.effortSetAverages().size
            val isFading = powerAnalyzer.isPowerFading()
            val recovDeclining = hrAnalyzer.isRecoveryQualityDeclining()
            val compliance = powerAnalyzer.complianceScore()

            _context.update { c ->
                c.copy(
                    workout = ws.copy(
                        completedEffortCount = completedEfforts,
                        totalEffortCount = ws.totalSteps / 2, // heuristic: roughly half the steps
                        complianceScore = compliance,
                        recoveryQuality = if (hrAnalyzer.lastRecoveryDropRate() > 0)
                            (hrAnalyzer.lastRecoveryDropRate() / 0.5f).coerceIn(0f, 1f) else 1f,
                        powerFadingTrend = isFading,
                        recoveryQualityDeclining = recovDeclining,
                        effortAvgPowers = powerAnalyzer.effortSetAverages(),
                        recoveryDropRates = hrAnalyzer.recoveryDropRateHistory,
                    ),
                    inFirstIntervalOfSession = ws.currentStep <= 1,
                )
            }
        }
    }

    private suspend fun tickLoop() {
        while (true) {
            val nowSec = System.currentTimeMillis() / 1000
            val elapsed = nowSec - rideStartEpochSec
            val current = _context.value
            val z1Minutes = if (powerAnalyzer.isSustainedZ1(60)) {
                (current.minutesInZ1Sustained + (1f / 60f)).coerceAtMost(30f)
            } else 0f

            val baseRate = settingsRepo.current.carbTargetGramsPerHour
            val carbRate = FuelingIntelligence.recommendedCarbsPerHour(current, baseRate)

            // Compute target carbs based on elapsed time and dynamic demand.
            val elapsedHours = elapsed / 3600f
            val internalTargetCarbs = (elapsedHours * carbRate).toInt()
            val external = nomRideSignal?.takeIf { it.isFresh }
            val targetCarbs = external?.carbsEatenGrams?.let { eaten ->
                // If NomRide provides balance and eaten, derive a target that matches their accounting.
                val balance = external.carbBalanceGrams ?: 0
                (eaten - balance).coerceAtLeast(0)
            } ?: internalTargetCarbs
            val consumed = external?.carbsEatenGrams ?: current.carbsConsumedGrams
            val deficit = external?.carbBalanceGrams?.let { (-it).coerceAtLeast(0) }
                ?: (targetCarbs - consumed).coerceAtLeast(0)

            _context.update { c ->
                c.copy(
                    rideElapsedSec = elapsed,
                    isRecording = true,
                    minutesInZ1Sustained = z1Minutes,
                    carbsConsumedGrams = consumed,
                    carbTargetGrams = targetCarbs,
                    carbDeficitGrams = deficit,
                )
            }

            kotlinx.coroutines.delay(1000)
        }
    }
}
