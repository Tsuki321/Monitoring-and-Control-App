package com.watermonitor.app.data.ml

import com.watermonitor.app.data.model.FilterCondition
import com.watermonitor.app.data.model.FilterSpecs
import com.watermonitor.app.data.model.FilterStageHealth
import com.watermonitor.app.data.model.FilterStageSpec
import com.watermonitor.app.data.model.LimitedBy
import com.watermonitor.app.data.model.SensorData
import com.watermonitor.app.data.model.ServiceAction
import com.watermonitor.app.data.model.StageModelDiagnostics
import com.watermonitor.app.data.model.WearObservation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Replaces NaN and ±Infinity with a usable value.
 *
 * This exists because `coerceIn` does **not** sanitise NaN: Kotlin implements it as
 * `if (this < min) min else if (this > max) max else this`, and both comparisons are false
 * for NaN, so `Double.NaN.coerceIn(0.25, 4.0)` returns NaN. A NaN that reaches the view
 * layer rounds to 0 and renders as a plausible-looking empty progress bar rather than an
 * obvious crash — a silent wrong answer. Call this *before* every `coerceIn`, not after.
 */
internal fun Double.finiteOr(fallback: Double): Double = if (isFinite()) this else fallback

/** One stage's fitted coefficients plus the diagnostics used to display the fit. */
class StageModel(
    val stageKey: String,
    val coefficients: DoubleArray,
    val diagnostics: StageModelDiagnostics
)

/**
 * The filter-life model: maps water quality to a wear multiplier, accrues that wear against
 * a stage's rated life, and forecasts what is left.
 *
 * A pure `object` of side-effect-free functions over [SensorData], following the
 * `WaterQualityEvaluator` pattern already used in this project. State (accumulated usage,
 * service dates, stored observations) belongs to the repository, not here — which keeps all
 * of this unit-testable without Android.
 *
 * ### The maths
 * ```
 * L        = b0 + b1·tdsNorm + b2·turbNorm + b3·|pH − 7|     (fitted, clamped 0.25..4.0)
 * usageRun += (runtimeHoursDelta × L) / ratedHours
 * usageCal  = daysSinceService / ratedDays
 * u         = max(usageRun, usageCal)                        whichever limit binds first
 * health%   = 100 × (1 − u)
 * ```
 * Health is deliberately linear in `u`. Any strictly decreasing curve is just a relabelling
 * of the same information, and an exponential would show a filter at one third of its rated
 * life as 58% — which reads as "broken", not "two thirds left".
 *
 * ### Known bias
 * The fitted line is a surrogate for a curved process, so it does not pass exactly through
 * `L = 1.0` at nominal water quality. Measured against the current synthetic generator it
 * lands near 1.35 for the particulate stages (mesh, sand, rocks) and 0.85 for the adsorption
 * stages (carbon, charcoal), with R² between 0.68 and 0.85. In practice that means
 * particulate media reach their replace threshold roughly a third sooner than their nominal
 * rated hours, and carbon a little later. This is a property of fitting a line to a convex
 * clogging law, not a defect in the accrual — but it does mean `ratedHours` should be read
 * as "rated under this model", and it will shift once real service observations displace the
 * synthetic prior.
 */
object FilterLifeModel {

    /** Feature vector width: intercept, tdsNorm, turbNorm, |pH − 7|. */
    const val FEATURE_COUNT = 4

    const val MIN_LOAD_FACTOR = 0.25
    const val MAX_LOAD_FACTOR = 4.0
    const val NEUTRAL_LOAD_FACTOR = 1.0

    /** Below this duty cycle a wall-clock forecast is meaningless; show hours instead. */
    const val MIN_OBSERVABLE_DUTY_CYCLE = 0.001

    /**
     * How much a real serviced-stage observation outweighs one synthetic row. Applied as
     * weighted least squares (see [train]), so eight real observations carry roughly a third
     * of the fit against 400 synthetic rows.
     */
    private const val REAL_SAMPLE_WEIGHT = 25.0

    /** A rinse that recovers less than this much of `u` is not worth offering. */
    private const val RINSE_MEANINGFUL_RECOVERY = 0.10

    // ── Features ───────────────────────────────────────────────────────────

    fun features(data: SensorData): DoubleArray =
        features(data.tds.toDouble(), data.turbidity, data.ph)

    /**
     * Builds the feature row. Normalisation uses the **firmware's** published scales:
     * turbidity is 0–3000 NTU (`UPDATED_CODE_V16.ino:270-271`), not 0–5. Getting this wrong
     * by 600× would pin every reading at full scale and burn a cartridge in days.
     */
    fun features(tdsPpm: Double, turbidityNtu: Double, ph: Double): DoubleArray {
        val tdsNorm = (tdsPpm / FilterSpecs.TDS_FULL_SCALE_PPM).finiteOr(0.0).coerceIn(0.0, 1.0)
        val turbidityNorm = (turbidityNtu / FilterSpecs.TURBIDITY_FULL_SCALE_NTU)
            .finiteOr(0.0).coerceIn(0.0, 1.0)
        val phDeviation = abs(ph - 7.0).finiteOr(0.0).coerceIn(0.0, 7.0)
        return doubleArrayOf(1.0, tdsNorm, turbidityNorm, phDeviation)
    }

    private fun features(observation: WearObservation): DoubleArray = doubleArrayOf(
        1.0,
        observation.tdsNorm.finiteOr(0.0).coerceIn(0.0, 1.0),
        observation.turbidityNorm.finiteOr(0.0).coerceIn(0.0, 1.0),
        observation.phDeviation.finiteOr(0.0).coerceIn(0.0, 7.0)
    )

    // ── Inference ──────────────────────────────────────────────────────────

    /**
     * The wear multiplier for the current water. 1.0 means the stage is wearing at exactly
     * the pace its rated life assumes; 2.0 means twice as fast.
     *
     * Falls back to neutral rather than throwing when coefficients are missing or the
     * prediction is non-finite — an unknown load factor should stall the estimate, not
     * corrupt it.
     */
    fun loadFactor(coefficients: DoubleArray?, features: DoubleArray): Double {
        if (coefficients == null || coefficients.size != features.size) {
            return NEUTRAL_LOAD_FACTOR
        }
        return RidgeRegression.predict(features, coefficients)
            .finiteOr(NEUTRAL_LOAD_FACTOR)
            .coerceIn(MIN_LOAD_FACTOR, MAX_LOAD_FACTOR)
    }

    fun loadFactor(coefficients: DoubleArray?, data: SensorData): Double =
        loadFactor(coefficients, features(data))

    /**
     * Runtime wear added by one interval. Pass a neutral [loadFactor] for long intervals —
     * a delta covering three days of app-closed time must not be multiplied by whatever the
     * water happens to look like at the moment the app reopens.
     */
    fun usageDelta(spec: FilterStageSpec, runtimeHours: Double, loadFactor: Double): Double {
        if (spec.ratedHours <= 0.0) return 0.0
        val hours = runtimeHours.finiteOr(0.0).coerceAtLeast(0.0)
        val load = loadFactor.finiteOr(NEUTRAL_LOAD_FACTOR)
            .coerceIn(MIN_LOAD_FACTOR, MAX_LOAD_FACTOR)
        return ((hours * load) / spec.ratedHours).finiteOr(0.0).coerceAtLeast(0.0)
    }

    fun condition(usage: Double): FilterCondition {
        val u = usage.finiteOr(0.0)
        return when {
            u < 0.50 -> FilterCondition.GOOD
            u < 0.75 -> FilterCondition.MONITOR
            u < 0.95 -> FilterCondition.REPLACE_SOON
            u <= 1.00 -> FilterCondition.REPLACE_NOW
            else -> FilterCondition.OVERDUE
        }
    }

    /** Runtime usage left after a rinse. Calendar age is untouched — rinsing is not renewal. */
    fun usageAfterRinse(usageRuntime: Double): Double =
        (usageRuntime.finiteOr(0.0) * (1.0 - FilterSpecs.RINSE_RECOVERY)).coerceAtLeast(0.0)

    /**
     * Turns a completed service interval into a training row's target: how fast this stage
     * actually wore, relative to its rating. 1.0 = lasted exactly as rated; 2.0 = wore out
     * in half the rated hours.
     */
    fun observedWearRate(spec: FilterStageSpec, rawHoursSurvived: Double): Double {
        val hours = rawHoursSurvived.finiteOr(0.0)
        if (spec.ratedHours <= 0.0 || hours <= 0.0) return NEUTRAL_LOAD_FACTOR
        return (spec.ratedHours / hours)
            .finiteOr(NEUTRAL_LOAD_FACTOR)
            .coerceIn(MIN_LOAD_FACTOR, MAX_LOAD_FACTOR)
    }

    // ── Forecast ───────────────────────────────────────────────────────────

    /**
     * Full health readout for one stage.
     *
     * Both the runtime and the calendar branch are always computed and the tighter one wins.
     * Computing only the runtime branch when calendar actually binds would give a confidently
     * optimistic answer — the failure mode that matters here.
     *
     * @param dutyCycle observed runtime hours per wall-clock hour. Null or effectively zero
     *   means [FilterStageHealth.daysRemaining] comes back null and the UI must show
     *   operating hours rather than invent a date.
     */
    fun evaluate(
        spec: FilterStageSpec,
        usageRuntime: Double,
        daysSinceService: Double,
        loadFactor: Double,
        weightedHours: Double,
        dutyCycle: Double?
    ): FilterStageHealth {
        val runtimeUsage = usageRuntime.finiteOr(0.0).coerceAtLeast(0.0)
        val days = daysSinceService.finiteOr(0.0).coerceAtLeast(0.0)
        val load = loadFactor.finiteOr(NEUTRAL_LOAD_FACTOR)
            .coerceIn(MIN_LOAD_FACTOR, MAX_LOAD_FACTOR)

        val calendarUsage = if (spec.ratedDays > 0.0) {
            (days / spec.ratedDays).finiteOr(0.0).coerceAtLeast(0.0)
        } else {
            0.0
        }

        val usage = max(runtimeUsage, calendarUsage)
        val limitedBy = if (calendarUsage > runtimeUsage) LimitedBy.CALENDAR else LimitedBy.RUNTIME
        val health = (100.0 * (1.0 - usage)).finiteOr(0.0).coerceIn(0.0, 100.0)
        val condition = condition(usage)

        val hoursRemaining = if (spec.ratedHours > 0.0) {
            (((1.0 - runtimeUsage) * spec.ratedHours) / load).finiteOr(0.0).coerceAtLeast(0.0)
        } else {
            0.0
        }

        val calendarDaysRemaining = if (spec.ratedDays > 0.0) {
            ((1.0 - calendarUsage) * spec.ratedDays).finiteOr(0.0).coerceAtLeast(0.0)
        } else {
            null
        }

        // Operating hours are not wall-clock days; converting needs the duty cycle.
        val duty = dutyCycle?.finiteOr(0.0) ?: 0.0
        val runtimeDaysRemaining = if (duty > MIN_OBSERVABLE_DUTY_CYCLE && spec.ratedHours > 0.0) {
            (hoursRemaining / (24.0 * duty)).finiteOr(0.0).coerceAtLeast(0.0)
        } else {
            null
        }

        val daysRemaining = listOfNotNull(runtimeDaysRemaining, calendarDaysRemaining).minOrNull()

        // A rinse only restores runtime wear. If calendar age is what is killing the stage,
        // rinsing changes nothing — and a user who rinses and sees no movement concludes the
        // feature is broken, so say "replace" instead.
        //
        // Gated on the stage actually needing service: on nearly-new media the recovery is a
        // small *absolute* delta simply because there is little wear to recover, which would
        // otherwise disable the rinse button on a fresh filter and tell the user to replace it.
        val usageAfterRinse = max(usageAfterRinse(runtimeUsage), calendarUsage)
        val rinseWontHelp = spec.action == ServiceAction.RINSE &&
            condition.severity >= FilterCondition.REPLACE_SOON.severity &&
            (usage - usageAfterRinse) < RINSE_MEANINGFUL_RECOVERY

        return FilterStageHealth(
            stageIndex = spec.index,
            stageKey = spec.key,
            action = spec.action,
            healthPercent = health,
            usage = usage,
            condition = condition,
            limitedBy = limitedBy,
            operatingHoursRemaining = hoursRemaining,
            daysRemaining = daysRemaining,
            weightedHours = weightedHours.finiteOr(0.0).coerceAtLeast(0.0),
            daysSinceService = days,
            loadFactor = load,
            rinseWontHelp = rinseWontHelp
        )
    }

    // ── Training ───────────────────────────────────────────────────────────

    /** Fits all five stages from synthetic data alone. Used on first run. */
    fun trainAll(
        observationsByStage: Map<String, List<WearObservation>> = emptyMap(),
        sampleCount: Int = SyntheticWearData.DEFAULT_SAMPLES_PER_STAGE
    ): List<StageModel> = FilterSpecs.stages.map { spec ->
        train(spec, observationsByStage[spec.key].orEmpty(), sampleCount)
    }

    /**
     * Fits one stage.
     *
     * Real observations are folded in by **weighted least squares**, implemented as row
     * scaling: OLS on `(√w·x, √w·y)` is identical to WLS with weight `w`, without
     * duplicating rows. Below [FilterSpecs.MIN_OBSERVATIONS_FOR_FIT] real observations the
     * synthetic fit stands alone as the prior — a handful of service events is not enough to
     * outvote the physics.
     *
     * @param prior kept when the solve is degenerate. Never substitute zeros: a zero
     *   coefficient vector silently reports every stage as wearing at the minimum rate.
     */
    fun train(
        spec: FilterStageSpec,
        observations: List<WearObservation> = emptyList(),
        sampleCount: Int = SyntheticWearData.DEFAULT_SAMPLES_PER_STAGE,
        prior: StageModel? = null
    ): StageModel {
        val synthetic = SyntheticWearData.generate(spec, sampleCount)

        val usable = observations.filter {
            it.stageKey == spec.key && it.observedWearRate.isFinite()
        }
        val useReal = usable.size >= FilterSpecs.MIN_OBSERVATIONS_FOR_FIT

        val design = ArrayList<DoubleArray>(synthetic.size + usable.size)
        val targets = ArrayList<Double>(synthetic.size + usable.size)
        design.addAll(synthetic.design)
        targets.addAll(synthetic.targets)

        if (useReal) {
            val scale = sqrt(REAL_SAMPLE_WEIGHT)
            usable.forEach { observation ->
                val row = features(observation)
                design.add(DoubleArray(row.size) { row[it] * scale })
                targets.add(
                    observation.observedWearRate
                        .coerceIn(MIN_LOAD_FACTOR, MAX_LOAD_FACTOR) * scale
                )
            }
        }

        val coefficients = RidgeRegression.fit(design, targets)
            ?: prior?.coefficients
            ?: fallbackCoefficients(spec)

        // Scored against the unweighted synthetic rows only, so the number stays comparable
        // across stages and is not inflated by the weighting applied above.
        val rSquared = RidgeRegression.rSquared(synthetic.design, synthetic.targets, coefficients)

        return StageModel(
            stageKey = spec.key,
            coefficients = coefficients,
            diagnostics = StageModelDiagnostics(
                stageKey = spec.key,
                coefficients = coefficients.toList(),
                rSquared = rSquared,
                syntheticSamples = synthetic.size,
                realObservations = usable.size,
                fittedOnRealData = useReal
            )
        )
    }

    /**
     * Hand-built coefficients used only if the ridge solve fails outright and there is no
     * prior. Constructed to give exactly `L = 1.0` at nominal water quality, splitting the
     * non-intercept weight between TDS and turbidity by the stage's own sensitivities.
     */
    private fun fallbackCoefficients(spec: FilterStageSpec): DoubleArray {
        val intercept = 0.55
        val remaining = NEUTRAL_LOAD_FACTOR - intercept

        val nominalTdsNorm = FilterSpecs.NOMINAL_TDS_PPM / FilterSpecs.TDS_FULL_SCALE_PPM
        val nominalTurbidityNorm =
            FilterSpecs.NOMINAL_TURBIDITY_NTU / FilterSpecs.TURBIDITY_FULL_SCALE_NTU

        val sensitivitySum = spec.tdsSensitivity + spec.turbiditySensitivity
        val tdsShare = if (sensitivitySum > 0.0) spec.tdsSensitivity / sensitivitySum else 0.5
        val turbidityShare = 1.0 - tdsShare

        val tdsCoefficient = if (nominalTdsNorm > 0.0) {
            remaining * tdsShare / nominalTdsNorm
        } else {
            0.0
        }
        val turbidityCoefficient = if (nominalTurbidityNorm > 0.0) {
            remaining * turbidityShare / nominalTurbidityNorm
        } else {
            0.0
        }

        return doubleArrayOf(intercept, tdsCoefficient, turbidityCoefficient, 0.25)
    }
}
