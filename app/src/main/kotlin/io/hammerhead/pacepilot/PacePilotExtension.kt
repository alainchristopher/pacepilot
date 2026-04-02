package io.hammerhead.pacepilot

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideState
import io.hammerhead.pacepilot.analytics.AnalyticsManager
import io.hammerhead.pacepilot.coaching.CoachingEngine
import io.hammerhead.pacepilot.detection.ModeDetector
import io.hammerhead.pacepilot.detection.ModeTransitionEngine
import io.hammerhead.pacepilot.fields.PacePilotNumericDataType
import io.hammerhead.pacepilot.fit.CoachingFitExporter
import io.hammerhead.pacepilot.history.RideHistoryRepository
import io.hammerhead.pacepilot.history.PostRideInsightsRepository
import io.hammerhead.pacepilot.history.PostRideIntelligence
import io.hammerhead.pacepilot.history.RideSummaryBuilder
import io.hammerhead.pacepilot.model.ActiveMode
import io.hammerhead.pacepilot.model.ModeSource
import io.hammerhead.pacepilot.model.RideMode
import io.hammerhead.pacepilot.model.currentMode
import io.hammerhead.pacepilot.settings.SettingsRepository
import io.hammerhead.pacepilot.state.ActiveRideSnapshot
import io.hammerhead.pacepilot.state.ActiveRideStateStore
import io.hammerhead.pacepilot.telemetry.TelemetryAggregator
import io.hammerhead.pacepilot.telemetry.WorkoutStreamCollector
import io.hammerhead.pacepilot.util.consumerFlow
import io.hammerhead.pacepilot.workout.WorkoutDetector
import io.hammerhead.pacepilot.workout.WorkoutTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class PacePilotExtension : KarooExtension("pacepilot", "1.0") {
    override val types: List<DataTypeImpl> by lazy {
        listOf(
            PacePilotNumericDataType(extension, "coaching_status") {
                val now = System.currentTimeMillis() / 1000
                val ctx = telemetryAggregator.rideContext.value
                when {
                    !settingsRepo.current.appEnabled || !isRideActive -> 0.0
                    ctx.silencedUntilSec > now -> 2.0
                    else -> 1.0
                }
            },
            PacePilotNumericDataType(extension, "zone_time_sec") {
                val zone = telemetryAggregator.rideContext.value.powerZone
                if (zone in 1..7) powerZoneTimeSec[zone - 1].toDouble() else 0.0
            },
            PacePilotNumericDataType(extension, "ride_score") {
                rideScore().toDouble()
            }
        )
    }

    private lateinit var karooSystem: KarooSystemService
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var historyRepo: RideHistoryRepository
    private lateinit var postRideInsightsRepo: PostRideInsightsRepository
    private lateinit var activeRideStateStore: ActiveRideStateStore
    private val fitExporter = CoachingFitExporter()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Zone-time accumulators (reset each ride)
    private val powerZoneTimeSec = IntArray(7)
    private val hrZoneTimeSec = IntArray(5)
    private var peakPowerWatts = 0
    private var peakHrBpm = 0

    // Components
    private lateinit var workoutDetector: WorkoutDetector
    private lateinit var workoutTracker: WorkoutTracker
    private lateinit var workoutCollector: WorkoutStreamCollector
    private lateinit var telemetryAggregator: TelemetryAggregator
    private lateinit var modeDetector: ModeDetector
    private lateinit var modeTransitionEngine: ModeTransitionEngine
    private lateinit var coachingEngine: CoachingEngine

    private var rideStateJob: Job? = null
    private var zoneTrackingJob: Job? = null
    private var stateSnapshotJob: Job? = null
    private var isRideActive = false

    private fun rideScore(): Int {
        val ctx = telemetryAggregator.rideContext.value
        val base = if (ctx.workout.isActive) {
            (ctx.workout.complianceScore * 100f).toInt()
        } else {
            100 - (ctx.carbDeficitGrams.coerceAtMost(60) / 2) - ctx.hrDecouplingPct.toInt()
        }
        return base.coerceIn(0, 100)
    }

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        Timber.i("PacePilot: onCreate")

        karooSystem = KarooSystemService(this)
        settingsRepo = SettingsRepository(this)
        historyRepo = RideHistoryRepository(this)
        postRideInsightsRepo = PostRideInsightsRepository(this)
        activeRideStateStore = ActiveRideStateStore(this)
        AnalyticsManager.init(application, settingsRepo)
        serviceScope.launch { historyRepo.load() }

        // Wire components
        workoutDetector = WorkoutDetector(karooSystem, serviceScope)
        workoutTracker = WorkoutTracker()
        workoutCollector = WorkoutStreamCollector(karooSystem, serviceScope)

        telemetryAggregator = TelemetryAggregator(
            karooSystem = karooSystem,
            workoutCollector = workoutCollector,
            workoutTracker = workoutTracker,
            settingsRepo = settingsRepo,
            scope = serviceScope,
        )

        modeDetector = ModeDetector(workoutDetector)

        modeTransitionEngine = ModeTransitionEngine(
            rideContext = telemetryAggregator.rideContext,
            onModeChange = { newMode -> handleModeChange(newMode) },
            scope = serviceScope,
        )

        coachingEngine = CoachingEngine(
            karooSystem = karooSystem,
            rideContext = telemetryAggregator.rideContext,
            settingsRepo = settingsRepo,
            historyProvider = { historyRepo.current },
            scope = serviceScope,
            onEventDispatched = { event, message, aiUpgraded ->
                val mode = telemetryAggregator.rideContext.value.currentMode
                fitExporter.onCoachingEvent(event, message, mode, aiUpgraded)
            },
        )

        karooSystem.connect {
            Timber.i("PacePilot: Karoo system connected")
            startRideStateListener()
        }
    }

    override fun onDestroy() {
        Timber.i("PacePilot: onDestroy")
        if (isRideActive) stopRide()
        rideStateJob?.cancel()
        serviceScope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }

    override fun startFit(emitter: Emitter<FitEffect>) {
        fitExporter.attach(emitter)
        emitter.setCancellable {
            fitExporter.detach()
        }
    }

    // ------------------------------------------------------------------
    // Ride state listener
    // ------------------------------------------------------------------

    private fun startRideStateListener() {
        rideStateJob = serviceScope.launch {
            karooSystem.consumerFlow<RideState>().collect { state ->
                Timber.d("PacePilot: RideState = $state")
                when (state) {
                    is RideState.Recording -> {
                        if (!isRideActive) startRide()
                    }
                    is RideState.Paused -> {
                        // Keep ride running on pause — coaching resumes on unpause
                    }
                    is RideState.Idle -> {
                        activeRideStateStore.clear()
                        if (isRideActive) stopRide()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Ride lifecycle
    // ------------------------------------------------------------------

    private fun startRide() {
        if (isRideActive) return
        val currentSettings = settingsRepo.current
        Timber.i("PacePilot: startRide — appEnabled=${currentSettings.appEnabled}")
        if (!currentSettings.appEnabled) {
            Timber.i("PacePilot: app disabled — skipping ride start")
            return
        }
        restoreIfRecentSnapshot()
        isRideActive = true
        Timber.i("PacePilot: ride started")

        telemetryAggregator.start()

        // Detect initial mode after a short wait for telemetry to settle
        serviceScope.launch {
            kotlinx.coroutines.delay(3_000)  // 3s to let streams warm up
            val workoutActive = workoutDetector.detect(timeoutMs = 5_000)
            val ctx = telemetryAggregator.rideContext.value
            val settings = settingsRepo.current
            val initialMode = modeDetector.detect(ctx, settings, workoutActive)

            telemetryAggregator.updateMode(initialMode)
            Timber.i("PacePilot: initial mode = ${initialMode.mode} (${initialMode.source})")

            // Track ride start
            AnalyticsManager.trackRideStarted(initialMode.mode, settingsRepo.current.llmProvider)

            // Announce mode to rider
            dispatchModeNotification(initialMode.mode)

            // Start monitoring for transitions
            modeTransitionEngine.start()
            workoutDetector.monitor()

            // Start coaching
            coachingEngine.start()
        }

        // Zone-time + peak tracking — 1 Hz sampling (not per-emission)
        zoneTrackingJob = serviceScope.launch {
            while (true) {
                val ctx = telemetryAggregator.rideContext.value
                if (ctx.powerZone > 0) {
                    val pz = (ctx.powerZone - 1).coerceIn(0, 6)
                    powerZoneTimeSec[pz]++
                }
                if (ctx.hrZone > 0) {
                    val hz = (ctx.hrZone - 1).coerceIn(0, 4)
                    hrZoneTimeSec[hz]++
                }
                if (ctx.powerWatts > peakPowerWatts) peakPowerWatts = ctx.powerWatts
                if (ctx.heartRateBpm > peakHrBpm) peakHrBpm = ctx.heartRateBpm
                kotlinx.coroutines.delay(1000)
            }
        }

        // Persist active-ride state every 30s so service restarts can recover quickly.
        stateSnapshotJob = serviceScope.launch {
            while (true) {
                saveActiveSnapshot()
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    private fun stopRide() {
        if (!isRideActive) return
        isRideActive = false
        Timber.i("PacePilot: ride stopped")
        zoneTrackingJob?.cancel()
        zoneTrackingJob = null
        stateSnapshotJob?.cancel()
        stateSnapshotJob = null

        // Capture stats before stopping engine (stop() logs them too)
        val coachingStats = coachingEngine.stats
        coachingEngine.stop()
        fitExporter.onRideEnd(coachingStats)

        // Save ride summary before stopping telemetry
        val ctx = telemetryAggregator.rideContext.value

        // Track ride completion
        AnalyticsManager.trackRideCompleted(
            durationMin = (ctx.rideElapsedSec / 60).toInt(),
            distanceKm = ctx.distanceKm,
            elevationM = ctx.elevationGainM,
            mode = ctx.currentMode,
            stats = coachingStats,
            aiProvider = settingsRepo.current.llmProvider,
        )

        if (ctx.rideElapsedSec > 300) {
            serviceScope.launch {
                withContext(NonCancellable) {
                val summary = RideSummaryBuilder.build(
                    ctx = ctx,
                    powerAnalyzer = telemetryAggregator.powerAnalyzer,
                    hrAnalyzer = telemetryAggregator.hrAnalyzer,
                    powerZoneTimeSec = powerZoneTimeSec.copyOf(),
                    hrZoneTimeSec = hrZoneTimeSec.copyOf(),
                    peakHrBpm = peakHrBpm,
                    peakPowerWatts = peakPowerWatts,
                    coachingStats = coachingStats,
                )
                historyRepo.saveRide(summary)
                val insight = PostRideIntelligence.build(historyRepo.current, summary)
                postRideInsightsRepo.save(insight)
                }
            }
        }

        telemetryAggregator.stop()
        workoutTracker.reset()

        // Reset per-ride accumulators
        powerZoneTimeSec.fill(0)
        hrZoneTimeSec.fill(0)
        peakPowerWatts = 0
        peakHrBpm = 0
        activeRideStateStore.clear()
    }

    private fun saveActiveSnapshot() {
        val ctx = telemetryAggregator.rideContext.value
        activeRideStateStore.save(
            ActiveRideSnapshot(
                savedAtEpochSec = System.currentTimeMillis() / 1000,
                rideElapsedSec = ctx.rideElapsedSec,
                modeName = ctx.currentMode.name,
                silencedUntilSec = ctx.silencedUntilSec,
                peakPowerWatts = peakPowerWatts,
                peakHrBpm = peakHrBpm,
                powerZoneTimesCsv = powerZoneTimeSec.joinToString(","),
                hrZoneTimesCsv = hrZoneTimeSec.joinToString(","),
            )
        )
    }

    private fun restoreIfRecentSnapshot() {
        val snapshot = activeRideStateStore.load() ?: return
        val ageSec = System.currentTimeMillis() / 1000 - snapshot.savedAtEpochSec
        if (ageSec > 8 * 3600) {
            activeRideStateStore.clear()
            return
        }
        parseCsvInto(snapshot.powerZoneTimesCsv, powerZoneTimeSec)
        parseCsvInto(snapshot.hrZoneTimesCsv, hrZoneTimeSec)
        peakPowerWatts = snapshot.peakPowerWatts
        peakHrBpm = snapshot.peakHrBpm
        telemetryAggregator.updateSilence(snapshot.silencedUntilSec)
        Timber.i("PacePilot: restored active snapshot (age=${ageSec}s)")
    }

    private fun parseCsvInto(csv: String, target: IntArray) {
        if (csv.isBlank()) return
        csv.split(",").forEachIndexed { idx, token ->
            if (idx < target.size) {
                target[idx] = token.toIntOrNull() ?: 0
            }
        }
    }

    // ------------------------------------------------------------------
    // Mode changes
    // ------------------------------------------------------------------

    private fun handleModeChange(newMode: ActiveMode) {
        val oldMode = telemetryAggregator.rideContext.value.currentMode
        Timber.i("PacePilot: mode changed to ${newMode.mode} (${newMode.source})")
        telemetryAggregator.updateMode(newMode)
        if (newMode.source == ModeSource.TRANSITION) {
            dispatchModeNotification(newMode.mode)
            AnalyticsManager.trackModeTransition(
                fromMode = oldMode,
                toMode = newMode.mode,
                trigger = "auto",
            )
        }
    }

    private fun dispatchModeNotification(mode: RideMode) {
        val message = when (mode) {
            RideMode.WORKOUT -> "Workout. Coaching active."
            RideMode.CLIMB_FOCUSED -> "Climb mode. Coaching on."
            RideMode.ENDURANCE -> "Endurance. Coaching active."
            RideMode.ADAPTIVE -> "Observing. Coach starts ~10min."
            RideMode.RECOVERY -> "Recovery mode. Stay Z1."
        }
        val detail = if (message.length > 30) message.take(28) + "…" else message
        karooSystem.dispatch(
            InRideAlert(
                id = "pp_mode_${mode.name.lowercase()}",
                icon = R.drawable.ic_pacepilot,
                title = "PacePilot",
                detail = detail,
                autoDismissMs = 8_000,
                backgroundColor = R.color.alert_bg_coaching,
                textColor = R.color.alert_text_coaching,
            )
        )
    }

    // ------------------------------------------------------------------
    // BonusActions
    // ------------------------------------------------------------------

    override fun onBonusAction(actionId: String) {
        Timber.i("PacePilot: BonusAction = $actionId")
        when (actionId) {
            "mode_workout" -> modeTransitionEngine.applyManualOverride(RideMode.WORKOUT)
            "mode_endurance" -> modeTransitionEngine.applyManualOverride(RideMode.ENDURANCE)
            "mode_climb" -> modeTransitionEngine.applyManualOverride(RideMode.CLIMB_FOCUSED)
            "mode_adaptive" -> modeTransitionEngine.applyManualOverride(RideMode.ADAPTIVE)
            "ack_fuel", "ack_eat" -> {
                val grams = settingsRepo.current.carbsPerFuelServing
                telemetryAggregator.acknowledgedEat(grams)
                AnalyticsManager.trackAlertInteracted("fuel_reminder", "ack_fuel")
                karooSystem.dispatch(
                    InRideAlert(
                        id = "pp_eat_ack",
                        icon = R.drawable.ic_pacepilot,
                        title = "Fuel Up",
                        detail = "+${grams}g carbs logged.",
                        autoDismissMs = 4_000,
                        backgroundColor = R.color.alert_bg_fuel,
                        textColor = R.color.alert_text_fuel,
                    )
                )
            }
            "ack_drink" -> {
                telemetryAggregator.acknowledgedDrink()
                AnalyticsManager.trackAlertInteracted("drink_reminder", "ack_drink")
                karooSystem.dispatch(
                    InRideAlert(
                        id = "pp_drink_ack",
                        icon = R.drawable.ic_pacepilot,
                        title = "Hydration",
                        detail = "Drink logged.",
                        autoDismissMs = 3_000,
                        backgroundColor = R.color.alert_bg_fuel,
                        textColor = R.color.alert_text_fuel,
                    )
                )
            }
            "snooze_15min" -> {
                val untilSec = System.currentTimeMillis() / 1000 + 900
                telemetryAggregator.updateSilence(untilSec)
                AnalyticsManager.trackAlertInteracted("global", "snooze_15min")
                karooSystem.dispatch(
                    InRideAlert(
                        id = "pp_snoozed_15",
                        icon = R.drawable.ic_pacepilot,
                        title = "PacePilot",
                        detail = "Snoozed for 15 min.",
                        autoDismissMs = 4_000,
                        backgroundColor = R.color.alert_bg_coaching,
                        textColor = R.color.alert_text_coaching,
                    )
                )
            }
            "undo_snooze" -> {
                telemetryAggregator.updateSilence(0L)
                karooSystem.dispatch(
                    InRideAlert(
                        id = "pp_snooze_undo",
                        icon = R.drawable.ic_pacepilot,
                        title = "PacePilot",
                        detail = "Snooze cleared.",
                        autoDismissMs = 3_000,
                        backgroundColor = R.color.alert_bg_positive,
                        textColor = R.color.alert_text_positive,
                    )
                )
            }
            "silence_10min" -> {
                val untilSec = System.currentTimeMillis() / 1000 + 600
                telemetryAggregator.updateSilence(untilSec)
                karooSystem.dispatch(
                    InRideAlert(
                        id = "pp_silenced",
                        icon = R.drawable.ic_pacepilot,
                        title = "PacePilot",
                        detail = "Coaching paused for 10 min.",
                        autoDismissMs = 4_000,
                        backgroundColor = R.color.alert_bg_coaching,
                        textColor = R.color.alert_text_coaching,
                    )
                )
            }
            else -> Timber.w("PacePilot: unknown BonusAction $actionId")
        }
    }
}
