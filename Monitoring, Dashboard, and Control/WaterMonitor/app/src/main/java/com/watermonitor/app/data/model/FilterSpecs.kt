package com.watermonitor.app.data.model

/**
 * How a stage is serviced when it wears out.
 *
 * REPLACE — consumable media, swapped for new. Resets runtime wear *and* calendar age.
 * RINSE   — reusable media, backwashed. Restores most of the runtime wear but does
 *           nothing for calendar age (media still oxidises/degrades on the shelf).
 */
enum class ServiceAction {
    REPLACE,
    RINSE
}

/**
 * Which limit is currently driving a stage's health — runtime hours or calendar age.
 * Surfaced in the UI because a RINSE stage bound by calendar cannot be fixed by rinsing,
 * and a user who rinses and sees no change concludes the feature is broken.
 */
enum class LimitedBy {
    RUNTIME,
    CALENDAR
}

/**
 * Static, tunable specification of the five biofilter stages.
 *
 * The physical order is confirmed: pumice, pebbles, lava rock, activated carbon, then sand.
 *
 * TODO(hardware): rated lifetimes, service actions and sensitivities are provisional estimates,
 * not measurements of this build. Replace them with manufacturer values or measured service
 * intervals once they are available.
 */
object FilterSpecs {

    /**
     * Multiplies simulated runtime so the feature can be demonstrated before the ESP32
     * publishes real counters. 1.0 = real time. Never applied to hardware counters.
     *
     * At the current 60× (with [SIMULATED_DUTY_CYCLE] 0.35) the app accrues 21 pump-hours
     * per wall-clock hour. The table below assumes a neutral load factor (`L = 1.0`), so it
     * is a pacing reference rather than a prediction for nominal or live readings:
     *
     * | stage            | rated pump-hours | neutral-load wall-clock hours to exhaust |
     * |------------------|------------------|------------------------------------------|
     * | pumice           | 2000h            |  ~95                                     |
     * | pebbles          | 3000h            | ~143                                     |
     * | lava rock        | 2500h            | ~119                                     |
     * | activated carbon |  600h            |  ~29                                     |
     * | sand             | 1200h            |  ~57                                     |
     *
     * Wear only accrues while the app is open and receiving samples, so a few minutes of
     * screen time moves a bar by well under a percent. To watch a stage walk GOOD → OVERDUE
     * inside a short demo, raise this to ~10000. At scales above roughly 286×, simulated
     * runtime exceeds the forecast's 100 pump-hours-per-wall-hour guard, so the condition bars
     * remain useful for the demo but the displayed remaining-days estimate does not track the
     * accelerated progression.
     *
     * TODO(demo): set back to 1.0 before final submission.
     */
    const val DEMO_TIME_SCALE: Double = 60.0

    /** Below this fraction of accumulated runtime wear, a rinse is considered effective. */
    const val RINSE_RECOVERY: Double = 0.70

    /**
     * Assumed fraction of wall-clock time the pumps run, used only while the ESP32 is not
     * yet publishing runtime counters. Combined with [DEMO_TIME_SCALE] this drives the
     * simulated wear the UI labels as "Simulated".
     */
    const val SIMULATED_DUTY_CYCLE: Double = 0.35

    /**
     * A stage must be at least this worn when replaced for the interval to become a training
     * row. Replacing a stage at 20% life would otherwise teach the model that media wears
     * out five times faster than it does.
     */
    const val MIN_USAGE_FOR_OBSERVATION: Double = 0.85

    /** Number of real serviced-stage observations required before the model re-fits. */
    const val MIN_OBSERVATIONS_FOR_FIT: Int = 8

    /** FIFO cap on stored observations per stage, keeping the prefs blob small. */
    const val MAX_OBSERVATIONS_PER_STAGE: Int = 32

    /**
     * Firmware clamps turbidity to 0..3000 NTU (UPDATED_CODE_V16.ino:270-271) and emits
     * exactly 3000 as a fault sentinel when trueSensorVolt < 2.5 (probe unpowered, dry or
     * disconnected). Normalise against this scale, and treat readings at the ceiling as a
     * fault rather than as filthy water.
     */
    const val TURBIDITY_FULL_SCALE_NTU: Double = 3000.0
    const val TURBIDITY_FAULT_THRESHOLD_NTU: Double = 2999.0

    /** TDS normalisation ceiling in ppm. Readings above this saturate the load factor. */
    const val TDS_FULL_SCALE_PPM: Double = 1000.0

    /**
     * The water quality that anchors the synthetic generator's non-linear ground truth at a
     * wear multiplier of 1.0. The fitted linear surrogate is unconstrained and can predict a
     * different multiplier at this point, so [FilterStageSpec.ratedHours] is a reference scale
     * rather than a guaranteed fitted lifetime. These are not display values.
     *
     * TODO(hardware): revise once the real feed water has been characterised.
     */
    const val NOMINAL_TDS_PPM: Double = 300.0
    const val NOMINAL_TURBIDITY_NTU: Double = 50.0

    /**
     * Nominal feed pressure and pump speed. The wear model normalises live readings
     * against these (a reading at nominal contributes a ratio of 1.0 — rated operating
     * condition), and the simulated telemetry reports them under normal water conditions.
     *
     * TODO(hardware): no pressure or RPM sensor exists in this build. The app simulates
     * these at nominal (±3% wobble) until firmware publishes `psi`/`rpm` under /sensors.
     */
    const val NOMINAL_PRESSURE_PSI: Double = 40.0
    const val NOMINAL_PUMP_RPM: Double = 1450.0

    val stages: List<FilterStageSpec> = listOf(
        FilterStageSpec(
            index = 0,
            key = "pumice",
            action = ServiceAction.RINSE,
            ratedHours = 2000.0,
            ratedDays = 730.0,
            wearProfile = WearProfile.PARTICULATE,
            // Porous first bed captures suspended solids before the downstream media.
            turbiditySensitivity = 0.7,
            tdsSensitivity = 0.1
        ),
        FilterStageSpec(
            index = 1,
            key = "pebbles",
            action = ServiceAction.RINSE,
            ratedHours = 3000.0,
            ratedDays = 730.0,
            wearProfile = WearProfile.PARTICULATE,
            // Coarse media traps larger particles and supports the finer beds.
            turbiditySensitivity = 0.35,
            tdsSensitivity = 0.05
        ),
        FilterStageSpec(
            index = 2,
            key = "lava_rock",
            action = ServiceAction.RINSE,
            ratedHours = 2500.0,
            ratedDays = 730.0,
            wearProfile = WearProfile.PARTICULATE,
            turbiditySensitivity = 0.6,
            tdsSensitivity = 0.1
        ),
        FilterStageSpec(
            index = 3,
            // Keep the existing persisted key so activated-carbon history survives the reorder.
            key = "carbon",
            action = ServiceAction.REPLACE,
            ratedHours = 600.0,
            ratedDays = 180.0,
            wearProfile = WearProfile.ADSORPTION,
            turbiditySensitivity = 0.2,
            tdsSensitivity = 1.0
        ),
        FilterStageSpec(
            index = 4,
            key = "sand",
            action = ServiceAction.RINSE,
            ratedHours = 1200.0,
            ratedDays = 365.0,
            wearProfile = WearProfile.PARTICULATE,
            // Final polishing layer takes the remaining fine suspended solids.
            turbiditySensitivity = 0.9,
            tdsSensitivity = 0.1
        )
    )

    fun spec(index: Int): FilterStageSpec = stages[index.coerceIn(0, stages.lastIndex)]
}

/**
 * The physical mechanism by which a stage wears out. Drives the synthetic training
 * generator's ground truth, so the linear model is fitting a genuinely non-linear
 * process rather than recovering its own coefficients.
 */
enum class WearProfile {
    /** Clogging: pore blockage accelerates as captured solids accumulate. */
    PARTICULATE,

    /** Adsorption: active sites saturate, so capacity decays toward exhaustion. */
    ADSORPTION
}

data class FilterStageSpec(
    val index: Int,
    /** Stable identifier used in the persisted prefs blob. Never localise this. */
    val key: String,
    val action: ServiceAction,
    val ratedHours: Double,
    val ratedDays: Double,
    val wearProfile: WearProfile,
    val turbiditySensitivity: Double,
    val tdsSensitivity: Double
)
