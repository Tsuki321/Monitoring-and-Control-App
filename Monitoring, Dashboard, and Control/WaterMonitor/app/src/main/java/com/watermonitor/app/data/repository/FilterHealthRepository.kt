package com.watermonitor.app.data.repository

import android.content.Context
import android.util.Log
import com.watermonitor.app.data.ml.FilterLifeModel
import com.watermonitor.app.data.ml.StageModel
import com.watermonitor.app.data.ml.finiteOr
import com.watermonitor.app.data.model.FilterHealthState
import com.watermonitor.app.data.model.FilterSpecs
import com.watermonitor.app.data.model.FilterStageSpec
import com.watermonitor.app.data.model.RuntimeSource
import com.watermonitor.app.data.model.SensorData
import com.watermonitor.app.data.model.ServiceAction
import com.watermonitor.app.data.model.WearObservation
import com.watermonitor.app.utils.FilterPersistedState
import com.watermonitor.app.utils.FilterPrefs
import com.watermonitor.app.utils.FilterStageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Owns filter wear state: accrues it from sensor samples, persists it, and publishes the
 * computed health of all five stages.
 *
 * `object` singleton to match the existing repository style in this package. All mutation
 * runs under a [Mutex] because samples arrive on the RTDB listener thread while service
 * actions arrive from the UI.
 *
 * ### Runtime sources
 * When `/sensors` carries `runtimeA`/`runtimeB` (firmware V17+) wear is driven by the real
 * cumulative counters. Until then a wall-clock simulation stands in, scaled by
 * [FilterSpecs.DEMO_TIME_SCALE], and [FilterHealthState.runtimeSource] reports `SIMULATED`
 * so the UI can say so plainly rather than presenting invented numbers as measurements.
 */
object FilterHealthRepository {

    private const val TAG = "HydroSenseFilter"

    /**
     * Longest interval the simulated path will bill, in hours — one firmware publish
     * period. Simulated wear models the app being open and receiving samples; a closed
     * gap is not screen time and must not inject a lump of fiction on reopen.
     */
    private const val MAX_SIMULATED_GAP_HOURS = 1.0 / 60.0

    /**
     * Above this delta the water quality at the moment of the sample is not representative
     * of the interval, so wear accrues at a neutral load factor instead.
     */
    private const val NEUTRAL_LOAD_ABOVE_HOURS = 1.0

    /** Amplitude and period of the simulated pressure/RPM wobble around nominal. */
    private const val TELEMETRY_WOBBLE = 0.03
    private const val TELEMETRY_WOBBLE_PERIOD_MS = 420_000.0

    /** Minimum gap between prefs writes. Protects against per-tick XML rewrites. */
    private const val SAVE_INTERVAL_MILLIS = 60_000L

    /** Upper bound on the reported duty cycle; only a guard against absurd stored values. */
    private const val MAX_PLAUSIBLE_DUTY_CYCLE = 100.0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(FilterHealthState())
    val filterHealthFlow: StateFlow<FilterHealthState> = _state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialized = false

    /**
     * Set only once the persisted blob has actually been read and the models fitted.
     *
     * Distinct from [initialized] on purpose. `initialize` returns immediately and loads on a
     * background dispatcher, so between those two moments `persisted` still holds
     * `FilterPrefs.default()`. A sample accepted in that window would accrue onto zeroed
     * state and then — since `lastSaveMillis` is 0, making the throttle interval elapsed —
     * write that zeroed state straight over the user's real wear history.
     */
    @Volatile
    private var stateLoaded = false

    private var persisted: FilterPersistedState = FilterPrefs.default()
    private var models: List<StageModel> = emptyList()
    private var lastSaveMillis = 0L

    /**
     * Whether the most recent sample carried firmware runtime counters. Tracked per sample
     * rather than read from the persisted counters, so a firmware downgrade re-labels the UI
     * as simulated instead of continuing to claim hardware.
     */
    private var hardwareRuntimeSeen = false

    /** Last feed pressure / pump speed for the telemetry line; simulated until real. */
    private var lastPressurePsi = FilterSpecs.NOMINAL_PRESSURE_PSI
    private var lastPumpRpm = FilterSpecs.NOMINAL_PUMP_RPM
    private var telemetryFromHardware = false

    /**
     * Loads state and fits the models. Safe to call more than once; only the first call does
     * work.
     *
     * Call this from [android.app.Application.onCreate], not from an Activity — an Activity
     * re-runs `onCreate` on every `recreate()`, which `SettingsFragment` triggers on theme
     * and locale changes.
     */
    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            initialized = true
        }

        // Prefs read and 5 × 400-row fits are far too much for the main thread.
        scope.launch {
            mutex.withLock {
                val ctx = appContext ?: return@withLock
                persisted = runCatching { FilterPrefs.load(ctx) }.getOrElse {
                    Log.w(TAG, "Failed to load filter state; starting fresh", it)
                    FilterPrefs.default()
                }
                models = fitModels()
                hardwareRuntimeSeen = persisted.lastRuntimeSecondsA != null ||
                    persisted.lastRuntimeSecondsB != null
                // Anchor the save throttle so the first sample cannot trip it instantly.
                lastSaveMillis = System.currentTimeMillis()
                stateLoaded = true
                publish()
            }
        }
    }

    /**
     * Ingests one RTDB sensor snapshot, accruing wear for the interval it represents.
     *
     * Callers must wrap this in `runCatching`: it is hooked upstream of a `shareIn`, and an
     * exception there cancels the sharing coroutine permanently — `WhileSubscribed` does not
     * restart after upstream failure, so one bad sample would kill sensor data app-wide
     * until the process dies.
     */
    suspend fun onSensorSample(data: SensorData) {
        if (!stateLoaded) return
        if (!isUsable(data)) return

        mutex.withLock {
            if (appContext == null) return@withLock
            accrue(data)
            publish()
            maybeSave()
        }
    }

    /**
     * Rinses a reusable stage: restores [FilterSpecs.RINSE_RECOVERY] of accumulated runtime
     * wear. Calendar age is untouched, because backwashing does not renew the media.
     */
    fun markRinsed(stageIndex: Int) {
        val spec = FilterSpecs.spec(stageIndex)
        scope.launch {
            mutex.withLock {
                if (!stateLoaded) return@withLock
                updateStage(spec.key) { stage ->
                    val recovery = 1.0 - FilterSpecs.RINSE_RECOVERY
                    stage.copy(
                        usageRuntime = FilterLifeModel.usageAfterRinse(stage.usageRuntime),
                        weightedHours = (stage.weightedHours * recovery).coerceAtLeast(0.0),
                        rawHours = (stage.rawHours * recovery).coerceAtLeast(0.0),
                        rinseCount = stage.rinseCount + 1,
                        // Condition sums are runtime-weighted, so they must shrink in step
                        // with rawHours or the recovered mean would be wrong.
                        sumTdsNorm = stage.sumTdsNorm * recovery,
                        sumTurbidityNorm = stage.sumTurbidityNorm * recovery,
                        sumPhDeviation = stage.sumPhDeviation * recovery
                    )
                }
                publish()
                forceSave()
            }
        }
    }

    /**
     * Replaces a stage's media: resets runtime wear and calendar age, and — if the stage ran
     * far enough to be informative — records a real training observation and re-fits.
     */
    fun markReplaced(stageIndex: Int) {
        val spec = FilterSpecs.spec(stageIndex)
        scope.launch {
            mutex.withLock {
                if (!stateLoaded) return@withLock
                val previous = stageState(spec.key)
                val observation = buildObservation(spec, previous)

                updateStage(spec.key) {
                    FilterStageState(
                        key = spec.key,
                        lastServiceWallMillis = System.currentTimeMillis()
                    )
                }

                if (observation != null) {
                    val kept = (persisted.observations + observation)
                        .groupBy { it.stageKey }
                        .flatMap { (_, rows) -> rows.takeLast(FilterSpecs.MAX_OBSERVATIONS_PER_STAGE) }
                    persisted = persisted.copy(observations = kept)
                    models = fitModels()
                    Log.d(
                        TAG,
                        "Recorded observation for ${spec.key}: wearRate=${observation.observedWearRate}"
                    )
                }

                publish()
                forceSave()
            }
        }
    }

    /**
     * Clears all wear history and returns every stage to new.
     *
     * Intentionally not gated on [stateLoaded] — writing defaults is the whole point here, and
     * it also resolves the load: whatever was on disk is now irrelevant.
     */
    fun resetAll() {
        scope.launch {
            mutex.withLock {
                val ctx = appContext ?: return@withLock
                persisted = FilterPrefs.default()
                models = fitModels()
                hardwareRuntimeSeen = false
                lastPressurePsi = FilterSpecs.NOMINAL_PRESSURE_PSI
                lastPumpRpm = FilterSpecs.NOMINAL_PUMP_RPM
                telemetryFromHardware = false
                FilterPrefs.clear(ctx)
                lastSaveMillis = System.currentTimeMillis()
                stateLoaded = true
                publish()
            }
        }
    }

    /** Flushes pending state. Call from `onStop` so at most one write survives the session. */
    fun flush() {
        scope.launch { mutex.withLock { if (stateLoaded) forceSave() } }
    }

    // ── Sample validation ──────────────────────────────────────────────────

    /**
     * Rejects samples that would corrupt the wear estimate.
     *
     * The turbidity check matters most: firmware emits exactly 3000 NTU as a *fault
     * sentinel* when `trueSensorVolt < 2.5` — probe unpowered, dry or disconnected
     * (`UPDATED_CODE_V16.ino:265-266`). Treated as a reading it pins the normalised feature
     * at 1.0, drives the load factor to its 4.0 ceiling, and consumes a 600-hour carbon stage
     * in 150 hours of running.
     */
    private fun isUsable(data: SensorData): Boolean {
        // On sign-out `flatMapLatest` emits `flowOf(SensorData())`, whose ph=7.0/tds=150/
        // turbidity=1.5 defaults are indistinguishable from real readings. Only RTDB
        // snapshots carry a timestamp.
        if (data.timestamp == 0L) return false
        if (!data.ph.isFinite() || !data.turbidity.isFinite()) return false
        if (data.turbidity >= FilterSpecs.TURBIDITY_FAULT_THRESHOLD_NTU) {
            Log.d(TAG, "Ignoring sample: turbidity ${data.turbidity} is the fault sentinel")
            return false
        }
        return true
    }

    // ── Accrual ────────────────────────────────────────────────────────────

    private fun accrue(data: SensorData) {
        val now = data.timestamp
        val previousWall = persisted.lastSampleWallMillis
        // Clock rolled backwards (timezone, NTP correction, user edit): re-anchor, accrue 0.
        // Deliberately NOT capped: the plausibility guard in [hardwareRuntimeHours] must see
        // the true gap, or a legitimate weekend of pumping looks like a garbage counter and
        // gets discarded. The duty-cycle denominator stays honest because the runtime
        // numerator is uncapped by the same amount.
        val wallDeltaHours = if (previousWall in 1 until now) {
            (now - previousWall) / 3_600_000.0
        } else {
            0.0
        }

        val hardware = data.runtimeASeconds != null || data.runtimeBSeconds != null
        hardwareRuntimeSeen = hardware
        val previouslySawHardware =
            persisted.lastRuntimeSecondsA != null || persisted.lastRuntimeSecondsB != null

        val runtimeHours: Double
        val wallHoursForDuty: Double
        val advanceWallAnchor: Boolean
        if (hardware) {
            if (!previouslySawHardware) {
                // First real counter after a simulated period: retire the fiction (see
                // [resetSimulatedAccrual]) so it cannot masquerade as measured history.
                resetSimulatedAccrual()
            }
            runtimeHours = hardwareRuntimeHours(data, wallDeltaHours)
            wallHoursForDuty = wallDeltaHours
            advanceWallAnchor = true
        } else if (previouslySawHardware) {
            // Counters vanished: firmware downgraded below V17, or a snapshot that omits
            // them. Runtime for the interval is unknown — simulating demo-scaled fiction
            // on top of measured history would be worse than accruing nothing.
            runtimeHours = 0.0
            wallHoursForDuty = 0.0
            advanceWallAnchor = false
        } else {
            runtimeHours = simulatedRuntimeHours(wallDeltaHours)
            wallHoursForDuty = min(wallDeltaHours, MAX_SIMULATED_GAP_HOURS)
            advanceWallAnchor = true
        }

        // Feed pressure and pump speed: real telemetry when the firmware publishes it (no
        // build does yet), otherwise simulated around nominal for normal water conditions.
        val hardwarePressure = data.pressurePsi?.takeIf { it.isFinite() }
        val hardwareRpm = data.pumpRpm?.takeIf { it.isFinite() }
        telemetryFromHardware = hardwarePressure != null || hardwareRpm != null
        lastPressurePsi = hardwarePressure ?: simulatedPressurePsi(now)
        lastPumpRpm = hardwareRpm ?: simulatedRpm(now)

        val features = FilterLifeModel.features(
            data.tds.toDouble(),
            data.turbidity,
            data.ph,
            lastPressurePsi,
            lastPumpRpm
        )
        val tdsNorm = features[1]
        val turbidityNorm = features[2]
        val phDeviation = features[3]

        if (runtimeHours > 0.0) {
            // A delta spanning hours of app-closed time must not be weighted by whatever
            // the water happens to look like at the instant the app reopens — and its
            // quality must not enter the condition sums either, or a later training row
            // would pair a long interval's wear with one instant's water.
            val useLiveLoad = runtimeHours <= NEUTRAL_LOAD_ABOVE_HOURS
            val stages = persisted.stages.map { stage ->
                val spec = specFor(stage.key) ?: return@map stage
                val load = if (useLiveLoad) {
                    FilterLifeModel.loadFactor(coefficientsFor(stage.key), features)
                } else {
                    FilterLifeModel.NEUTRAL_LOAD_FACTOR
                }
                val delta = FilterLifeModel.usageDelta(spec, runtimeHours, load)

                stage.copy(
                    usageRuntime = (stage.usageRuntime + delta).finiteOr(stage.usageRuntime),
                    weightedHours = stage.weightedHours + runtimeHours * load,
                    rawHours = stage.rawHours + runtimeHours,
                    sumTdsNorm = if (useLiveLoad) {
                        stage.sumTdsNorm + tdsNorm * runtimeHours
                    } else {
                        stage.sumTdsNorm
                    },
                    sumTurbidityNorm = if (useLiveLoad) {
                        stage.sumTurbidityNorm + turbidityNorm * runtimeHours
                    } else {
                        stage.sumTurbidityNorm
                    },
                    sumPhDeviation = if (useLiveLoad) {
                        stage.sumPhDeviation + phDeviation * runtimeHours
                    } else {
                        stage.sumPhDeviation
                    },
                    // First sample after install: anchor calendar age to now rather than to
                    // the epoch, or every stage reads as decades overdue.
                    lastServiceWallMillis = if (stage.lastServiceWallMillis == 0L) {
                        now
                    } else {
                        stage.lastServiceWallMillis
                    }
                )
            }
            persisted = persisted.copy(
                stages = stages,
                totalRuntimeHours = persisted.totalRuntimeHours + runtimeHours
            )
        } else if (persisted.stages.any { it.lastServiceWallMillis == 0L }) {
            persisted = persisted.copy(
                stages = persisted.stages.map {
                    if (it.lastServiceWallMillis == 0L) it.copy(lastServiceWallMillis = now) else it
                }
            )
        }

        persisted = persisted.copy(
            // While counters are absent the anchor stays frozen, so the delta when they
            // return is validated against the whole elapsed gap rather than one tick.
            lastSampleWallMillis = if (advanceWallAnchor) now else previousWall,
            accumulatedWallHours = persisted.accumulatedWallHours + wallHoursForDuty,
            lastRuntimeSecondsA = data.runtimeASeconds ?: persisted.lastRuntimeSecondsA,
            lastRuntimeSecondsB = data.runtimeBSeconds ?: persisted.lastRuntimeSecondsB
        )
    }

    /**
     * Runtime hours from the ESP32's cumulative counters.
     *
     * The counters are absolute, so a gap while the app was closed is captured in full and
     * costs nothing: the counter kept ticking whether or not the app was listening. Three
     * things are guarded:
     *
     * - **No previous reading** (first hardware sample, or the simulated → hardware
     *   switchover): anchor and accrue zero, or the switchover injects a delta of the
     *   device's entire lifetime.
     * - **Counter decreased** (reflash, NVS erase): re-anchor and accrue zero.
     * - **Delta exceeds wall time**: runtime cannot outrun the clock. Catches a
     *   restored-from-garbage counter that the decrease check alone would miss. 5% + 60s
     *   of slack absorbs clock skew. The comparison runs against the **uncapped** wall
     *   delta and an accepted delta is accrued in full — capping either side silently
     *   discarded every app-closed gap longer than ~6 hours of pump runtime.
     */
    private fun hardwareRuntimeHours(data: SensorData, wallDeltaHours: Double): Double {
        val currentA = data.runtimeASeconds
        val currentB = data.runtimeBSeconds
        val previousA = persisted.lastRuntimeSecondsA
        val previousB = persisted.lastRuntimeSecondsB

        if (previousA == null && previousB == null) {
            Log.d(TAG, "Anchoring to hardware runtime counters; accruing 0 for this sample")
            return 0.0
        }

        val deltaA = pumpDelta(currentA, previousA)
        val deltaB = pumpDelta(currentB, previousB)
        // Pumps A and B can run at once, and one hour of both running is one hour of water
        // through the filter — not two. Wear is bounded by wall time, so take the max.
        val deltaSeconds = max(deltaA, deltaB)
        if (deltaSeconds <= 0L) return 0.0

        val hours = deltaSeconds / 3600.0
        val allowance = wallDeltaHours * 1.05 + (60.0 / 3600.0)
        if (hours > allowance) {
            Log.w(TAG, "Rejecting runtime delta ${hours}h; exceeds wall clock ${wallDeltaHours}h")
            return 0.0
        }

        return hours.finiteOr(0.0)
    }

    private fun pumpDelta(current: Long?, previous: Long?): Long {
        if (current == null || previous == null) return 0L
        if (current < previous) {
            Log.w(TAG, "Runtime counter decreased ($previous → $current); treating as a reset")
            return 0L
        }
        return current - previous
    }

    /**
     * Retires everything the simulator invented when the first real runtime counter
     * arrives. Simulated runtime is demo-scaled fiction: keeping it would leave phantom
     * wear and an inflated duty cycle mixed into history that is then labelled
     * "measured". Calendar age survives — wall-clock time is real regardless of runtime
     * source — and so do rinse counts; observations recorded against simulated intervals
     * are dropped because their targets are scaled fiction too.
     */
    private fun resetSimulatedAccrual() {
        Log.i(TAG, "Hardware runtime counters appeared; retiring simulated wear history")
        persisted = persisted.copy(
            stages = persisted.stages.map { stage ->
                stage.copy(
                    usageRuntime = 0.0,
                    weightedHours = 0.0,
                    rawHours = 0.0,
                    sumTdsNorm = 0.0,
                    sumTurbidityNorm = 0.0,
                    sumPhDeviation = 0.0
                )
            },
            totalRuntimeHours = 0.0,
            accumulatedWallHours = 0.0,
            observations = emptyList()
        )
    }

    /**
     * Stand-in runtime while the firmware predates V17. Models the pumps running while the
     * app is open and receiving samples: the interval is capped at one publish period, so
     * reopening the app after hours does not bill the closed gap. Scaled by
     * [FilterSpecs.DEMO_TIME_SCALE]; the acceleration is intentional demo behaviour, not a
     * physical runtime measurement.
     */
    private fun simulatedRuntimeHours(wallDeltaHours: Double): Double {
        val sessionHours = wallDeltaHours.finiteOr(0.0).coerceIn(0.0, MAX_SIMULATED_GAP_HOURS)
        if (sessionHours <= 0.0) return 0.0
        return (sessionHours * FilterSpecs.SIMULATED_DUTY_CYCLE * FilterSpecs.DEMO_TIME_SCALE)
            .finiteOr(0.0).coerceAtLeast(0.0)
    }

    /**
     * Pressure/RPM stand-ins while no firmware publishes them: nominal with a slow
     * sinusoidal wobble, so the telemetry line reads like a live sensor without drifting
     * away from the normal-water assumption the wear model is anchored on.
     */
    private fun simulatedPressurePsi(nowMillis: Long): Double =
        (FilterSpecs.NOMINAL_PRESSURE_PSI *
            (1.0 + TELEMETRY_WOBBLE * sin(nowMillis / TELEMETRY_WOBBLE_PERIOD_MS)))
            .finiteOr(FilterSpecs.NOMINAL_PRESSURE_PSI)

    private fun simulatedRpm(nowMillis: Long): Double =
        (FilterSpecs.NOMINAL_PUMP_RPM *
            (1.0 + TELEMETRY_WOBBLE * sin(nowMillis / TELEMETRY_WOBBLE_PERIOD_MS + PI / 3.0)))
            .finiteOr(FilterSpecs.NOMINAL_PUMP_RPM)

    // ── Publishing ─────────────────────────────────────────────────────────

    private fun publish() {
        val now = System.currentTimeMillis()
        val duty = observedDutyCycle()

        val stages = FilterSpecs.stages.map { spec ->
            val stage = stageState(spec.key)
            val daysSinceService = if (stage.lastServiceWallMillis > 0L) {
                ((now - stage.lastServiceWallMillis) / 86_400_000.0).coerceAtLeast(0.0)
            } else {
                0.0
            }
            FilterLifeModel.evaluate(
                spec = spec,
                usageRuntime = stage.usageRuntime,
                daysSinceService = daysSinceService,
                loadFactor = lastLoadFactor(spec.key, stage),
                weightedHours = stage.weightedHours,
                dutyCycle = duty
            )
        }

        _state.value = FilterHealthState(
            stages = stages,
            runtimeSource = if (hardwareRuntimeSeen) {
                RuntimeSource.HARDWARE
            } else {
                RuntimeSource.SIMULATED
            },
            totalRuntimeHours = persisted.totalRuntimeHours,
            dutyCycle = duty,
            pressurePsi = lastPressurePsi,
            pumpRpm = lastPumpRpm,
            telemetrySimulated = !telemetryFromHardware,
            diagnostics = models.map { it.diagnostics },
            isWaitingForData = persisted.lastSampleWallMillis == 0L
        )
    }

    /**
     * Runtime hours per wall-clock hour. Null until measurable, which makes the UI show
     * operating hours instead of fabricating a calendar date from an unknown duty cycle.
     *
     * Deliberately **not** clamped to 1.0. Real hardware cannot exceed it — the delta is
     * bounded by wall time in [hardwareRuntimeHours] — but the simulated path runs at
     * [FilterSpecs.DEMO_TIME_SCALE]. The ceiling is an absurdity backstop; above roughly 286×
     * demo scale it also means the date forecast no longer tracks accelerated health exactly.
     */
    private fun observedDutyCycle(): Double? {
        val wall = persisted.accumulatedWallHours
        if (wall <= 0.0) return null
        val duty = (persisted.totalRuntimeHours / wall).finiteOr(0.0)
        return if (duty > FilterLifeModel.MIN_OBSERVABLE_DUTY_CYCLE) {
            duty.coerceAtMost(MAX_PLAUSIBLE_DUTY_CYCLE)
        } else {
            null
        }
    }

    /** Mean load factor over the stage's life so far; neutral before any runtime accrues. */
    private fun lastLoadFactor(key: String, stage: FilterStageState): Double {
        if (stage.rawHours <= 0.0) return FilterLifeModel.NEUTRAL_LOAD_FACTOR
        return FilterLifeModel.loadFactor(
            coefficientsFor(key),
            doubleArrayOf(
                1.0,
                stage.sumTdsNorm / stage.rawHours,
                stage.sumTurbidityNorm / stage.rawHours,
                stage.sumPhDeviation / stage.rawHours,
                // Hydraulic history is not persisted: simulated telemetry sat at nominal
                // by definition, and observation rows carry nominal ratios until real
                // pressure/RPM sensors land (TODO(hardware)).
                1.0,
                1.0
            )
        )
    }

    // ── Training ───────────────────────────────────────────────────────────

    private fun fitModels(): List<StageModel> {
        val byStage = persisted.observations.groupBy { it.stageKey }
        val previous = models.associateBy { it.stageKey }
        return FilterSpecs.stages.map { spec ->
            runCatching {
                FilterLifeModel.train(
                    spec = spec,
                    observations = byStage[spec.key].orEmpty(),
                    prior = previous[spec.key]
                )
            }.getOrElse {
                Log.w(TAG, "Fit failed for ${spec.key}; keeping prior", it)
                previous[spec.key] ?: FilterLifeModel.train(spec, emptyList())
            }
        }
    }

    /**
     * Turns a completed service interval into a training row — but only when the interval is
     * actually informative.
     *
     * Two intervals are discarded: those ending well short of rated life (replacing a stage
     * at 20% would teach the model that media wears five times faster than it does), and
     * those containing a rinse (the recovery is modelled, not measured, so the surviving
     * hours are not a clean observation).
     */
    private fun buildObservation(
        spec: FilterStageSpec,
        stage: FilterStageState
    ): WearObservation? {
        if (stage.rawHours <= 0.0) return null
        if (stage.usageRuntime < FilterSpecs.MIN_USAGE_FOR_OBSERVATION) return null
        if (spec.action == ServiceAction.RINSE && stage.rinseCount > 0) return null

        return WearObservation(
            stageKey = spec.key,
            tdsNorm = (stage.sumTdsNorm / stage.rawHours).finiteOr(0.0).coerceIn(0.0, 1.0),
            turbidityNorm = (stage.sumTurbidityNorm / stage.rawHours)
                .finiteOr(0.0).coerceIn(0.0, 1.0),
            phDeviation = (stage.sumPhDeviation / stage.rawHours).finiteOr(0.0).coerceIn(0.0, 7.0),
            observedWearRate = FilterLifeModel.observedWearRate(spec, stage.rawHours)
        )
    }

    // ── State plumbing ─────────────────────────────────────────────────────

    private fun specFor(key: String): FilterStageSpec? =
        FilterSpecs.stages.firstOrNull { it.key == key }

    private fun coefficientsFor(key: String): DoubleArray? =
        models.firstOrNull { it.stageKey == key }?.coefficients

    private fun stageState(key: String): FilterStageState =
        persisted.stages.firstOrNull { it.key == key } ?: FilterStageState(key = key)

    private fun updateStage(key: String, transform: (FilterStageState) -> FilterStageState) {
        val existing = persisted.stages.firstOrNull { it.key == key }
        val stages = if (existing == null) {
            persisted.stages + transform(FilterStageState(key = key))
        } else {
            persisted.stages.map { if (it.key == key) transform(it) else it }
        }
        persisted = persisted.copy(stages = stages)
    }

    /**
     * Writes at most once per [SAVE_INTERVAL_MILLIS]. Wear accrues every 5 seconds once
     * firmware V17 lands, and a prefs write per tick rewrites the whole XML file.
     */
    private fun maybeSave() {
        val now = System.currentTimeMillis()
        // Guard against a clock jump leaving lastSaveMillis in the future and stalling writes.
        if (lastSaveMillis > now) lastSaveMillis = 0L
        if (now - lastSaveMillis < SAVE_INTERVAL_MILLIS) return
        forceSave()
    }

    private fun forceSave() {
        val ctx = appContext ?: return
        FilterPrefs.save(ctx, persisted)
        lastSaveMillis = System.currentTimeMillis()
    }
}
