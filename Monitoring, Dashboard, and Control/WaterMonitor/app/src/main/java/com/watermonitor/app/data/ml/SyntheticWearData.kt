package com.watermonitor.app.data.ml

import com.watermonitor.app.data.model.FilterSpecs
import com.watermonitor.app.data.model.FilterStageSpec
import com.watermonitor.app.data.model.WearProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** A fitted model's input rows and targets. `design` rows already carry the intercept 1.0. */
data class TrainingSet(
    val design: List<DoubleArray>,
    val targets: List<Double>
) {
    val size: Int get() = targets.size
}

/**
 * Bootstrap training data for the filter-life model, generated on device from a seeded LCG
 * rather than shipped as an asset — reproducible, no `assets/` directory, no main-thread
 * JSON parse.
 *
 * The point worth stating plainly: **the ground truth here is non-linear.** Particulate
 * stages wear by a Carman–Kozeny-style pore-blockage law (superlinear — a clogging bed
 * clogs faster as it clogs), adsorption stages by Freundlich capacity saturation
 * (sublinear — doubling the contaminant less than doubles the wear rate, because capacity
 * rises with concentration too). The linear model in [FilterLifeModel] is therefore a
 * genuine surrogate fit to a curved process, not a circular recovery of constants that
 * were fed straight in.
 *
 * Water conditions are sampled over the range this system actually sees, skewed toward
 * typical values with a tail for excursions. Because features are normalised on the
 * firmware's published scales (TDS/1000, turbidity/3000) while real turbidity occupies
 * only the bottom slice of 0–3000 NTU, the fitted turbidity coefficient comes out large;
 * that is the regression correctly absorbing the scale mismatch from the data.
 *
 * Pure Kotlin, no Android imports.
 */
object SyntheticWearData {

    const val DEFAULT_SAMPLES_PER_STAGE = 400

    // Carman–Kozeny clogging.
    private const val CLOG_COEFFICIENT = 0.18
    private const val BED_POROSITY = 0.50

    // Freundlich isotherm exponent. n > 1 makes wear rate concave in concentration.
    private const val FREUNDLICH_N = 2.2

    // Off-neutral pH degrades media; the exponent makes it bite at the extremes only.
    private const val PH_COEFFICIENT = 0.28
    private const val PH_EXPONENT = 1.6

    // Heteroscedastic noise: harsher conditions are also less predictable.
    private const val NOISE_BASE = 0.03
    private const val NOISE_SLOPE = 0.12

    // Sampled condition ranges. Skew > 1 pushes the mass toward the low end.
    private const val TDS_MIN_PPM = 40.0
    private const val TDS_MAX_PPM = 900.0
    private const val TDS_SKEW = 1.8
    private const val TURBIDITY_MIN_NTU = 0.5
    private const val TURBIDITY_MAX_NTU = 600.0
    private const val TURBIDITY_SKEW = 3.5
    private const val PH_SIGMA = 0.5
    private const val PH_EXCURSION_RATE = 0.15
    private const val PH_MIN = 5.0
    private const val PH_MAX = 9.5

    // Hydraulic ranges as ratios of nominal. Tight and skewed toward 1.0 — normal water
    // is the common case; the tails exist so the fit can identify the sensitivities.
    private const val PRESSURE_RATIO_MIN = 0.75
    private const val PRESSURE_RATIO_MAX = 1.25
    private const val PRESSURE_RATIO_SKEW = 1.5
    private const val RPM_RATIO_MIN = 0.8
    private const val RPM_RATIO_MAX = 1.2
    private const val RPM_RATIO_SKEW = 1.5

    // Hydraulic wear exponents: pump speed drives flow (and throughput) roughly linearly;
    // the square-root pressure term is the extra push a partially clogged bed needs.
    private const val PRESSURE_EXPONENT = 0.5
    private const val RPM_EXPONENT = 1.0

    /** Per-stage seed base, so every stage gets a different but fixed sample sequence. */
    private const val SEED_BASE = 0x5EED_0000L

    /**
     * Builds one stage's bootstrap training set. Deterministic: the same spec and count
     * always produce identical rows, which is what makes the reported coefficients quotable
     * in a writeup.
     */
    fun generate(
        spec: FilterStageSpec,
        sampleCount: Int = DEFAULT_SAMPLES_PER_STAGE
    ): TrainingSet {
        val count = sampleCount.coerceAtLeast(FilterLifeModel.FEATURE_COUNT)
        val rng = Lcg(SEED_BASE + spec.index)
        val design = ArrayList<DoubleArray>(count)
        val targets = ArrayList<Double>(count)

        repeat(count) {
            val tdsPpm = rng.skewed(TDS_MIN_PPM, TDS_MAX_PPM, TDS_SKEW)
            val turbidityNtu = rng.skewed(TURBIDITY_MIN_NTU, TURBIDITY_MAX_NTU, TURBIDITY_SKEW)
            // Mostly near-neutral, with a minority of wide excursions so the pH coefficient
            // is identifiable rather than fitted to noise around 7.0.
            val ph = if (rng.nextDouble() < PH_EXCURSION_RATE) {
                PH_MIN + (PH_MAX - PH_MIN) * rng.nextDouble()
            } else {
                (7.0 + rng.nextGaussian() * PH_SIGMA).coerceIn(PH_MIN, PH_MAX)
            }
            val pressurePsi = FilterSpecs.NOMINAL_PRESSURE_PSI *
                rng.skewed(PRESSURE_RATIO_MIN, PRESSURE_RATIO_MAX, PRESSURE_RATIO_SKEW)
            val pumpRpm = FilterSpecs.NOMINAL_PUMP_RPM *
                rng.skewed(RPM_RATIO_MIN, RPM_RATIO_MAX, RPM_RATIO_SKEW)

            val truth = trueWearRate(spec, ph, tdsPpm, turbidityNtu) *
                hydraulicFactor(
                    pressurePsi / FilterSpecs.NOMINAL_PRESSURE_PSI,
                    pumpRpm / FilterSpecs.NOMINAL_PUMP_RPM
                )
            val noise = rng.nextGaussian() * (NOISE_BASE + NOISE_SLOPE * truth)
            val observed = (truth + noise)
                .finiteOr(FilterLifeModel.NEUTRAL_LOAD_FACTOR)
                .coerceIn(FilterLifeModel.MIN_LOAD_FACTOR, FilterLifeModel.MAX_LOAD_FACTOR)

            design.add(FilterLifeModel.features(tdsPpm, turbidityNtu, ph, pressurePsi, pumpRpm))
            targets.add(observed)
        }

        return TrainingSet(design, targets)
    }

    /**
     * Noise-free ground truth: the wear multiplier this stage would experience in water of
     * the given quality. 1.0 at [FilterSpecs.NOMINAL_TDS_PPM] / [FilterSpecs.NOMINAL_TURBIDITY_NTU]
     * and neutral pH, by construction.
     */
    fun trueWearRate(
        spec: FilterStageSpec,
        ph: Double,
        tdsPpm: Double,
        turbidityNtu: Double
    ): Double {
        val nominalLoad = spec.turbiditySensitivity + spec.tdsSensitivity
        if (nominalLoad <= 0.0) return FilterLifeModel.NEUTRAL_LOAD_FACTOR

        val load = spec.turbiditySensitivity * (turbidityNtu / FilterSpecs.NOMINAL_TURBIDITY_NTU) +
            spec.tdsSensitivity * (tdsPpm / FilterSpecs.NOMINAL_TDS_PPM)
        // 1.0 = this stage's rated operating condition.
        val relativeLoad = (load / nominalLoad).finiteOr(1.0).coerceAtLeast(0.0)

        val base = when (spec.wearProfile) {
            // Superlinear: captured solids shrink the pores, which raises capture rate.
            WearProfile.PARTICULATE ->
                kozenyResistance(relativeLoad) / kozenyResistance(1.0)

            // Sublinear: adsorption capacity itself grows with concentration (q = K·C^(1/n)),
            // so bed life falls as C^(1 - 1/n), not as C.
            WearProfile.ADSORPTION ->
                relativeLoad.coerceAtLeast(1e-3).pow(1.0 - 1.0 / FREUNDLICH_N)
        }

        val phFactor = 1.0 + PH_COEFFICIENT * abs(ph - 7.0).pow(PH_EXPONENT)
        return (base * phFactor).finiteOr(FilterLifeModel.NEUTRAL_LOAD_FACTOR)
    }

    /**
     * Hydraulic contribution to the wear multiplier: how much faster the media ages when
     * the feed is pushed harder or faster than nominal. Exactly 1.0 at nominal pressure
     * and nominal pump speed, by construction, so the overall anchor is unchanged.
     */
    private fun hydraulicFactor(pressureRatio: Double, rpmRatio: Double): Double =
        (pressureRatio.coerceAtLeast(0.0).pow(PRESSURE_EXPONENT) *
            rpmRatio.coerceAtLeast(0.0).pow(RPM_EXPONENT))
            .finiteOr(1.0)

    /**
     * Carman–Kozeny bed resistance, `(1-ε)² / ε³`, with porosity ε falling as deposit
     * accumulates. Returned as a raw resistance; callers normalise against the rated load.
     */
    private fun kozenyResistance(relativeLoad: Double): Double {
        val deposit = (CLOG_COEFFICIENT * relativeLoad).coerceIn(0.0, 0.9)
        val porosity = (BED_POROSITY * (1.0 - deposit)).coerceAtLeast(0.02)
        val solidFraction = 1.0 - porosity
        return (solidFraction * solidFraction) / (porosity * porosity * porosity)
    }

    /**
     * Linear congruential generator (the PCG/Knuth multiplier). Deliberately not
     * `java.util.Random` or `kotlin.random.Random` so the sequence is pinned to this code
     * and cannot shift under us with a platform change.
     */
    private class Lcg(seed: Long) {
        private var state: Long = seed

        init {
            // Warm up: a small seed otherwise leaks into the first few draws.
            repeat(4) { next() }
        }

        private fun next(): Long {
            state = state * 6364136223846793005L + 1442695040888963407L
            return state
        }

        /** Uniform in [0, 1). Takes the high 53 bits, where LCG output is best behaved. */
        fun nextDouble(): Double =
            (next() ushr 11).toDouble() / (1L shl 53).toDouble()

        /** Standard normal via Box–Muller. */
        fun nextGaussian(): Double {
            val u1 = nextDouble().coerceAtLeast(1e-12)
            val u2 = nextDouble()
            return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
        }

        /** Skewed toward [min] for [skew] > 1, uniform at 1.0. */
        fun skewed(min: Double, max: Double, skew: Double): Double =
            min + (max - min) * nextDouble().pow(skew)
    }
}
