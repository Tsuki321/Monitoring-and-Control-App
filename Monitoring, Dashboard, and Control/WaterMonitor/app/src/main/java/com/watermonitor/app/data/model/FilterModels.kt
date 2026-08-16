package com.watermonitor.app.data.model

/**
 * Health band for one filter stage. Ordered worst-last so `maxOf`/`sortedBy` can pick the
 * stage that most needs attention.
 */
enum class FilterCondition(val severity: Int) {
    GOOD(0),
    MONITOR(1),
    REPLACE_SOON(2),
    REPLACE_NOW(3),
    OVERDUE(4)
}

/** Where the runtime figures came from, so the UI can be honest about it. */
enum class RuntimeSource {
    /** No RTDB runtime field yet — figures come from the simulator. */
    SIMULATED,

    /** Live cumulative counters published by ESP32 firmware V17+. */
    HARDWARE
}

/**
 * Computed health of a single stage. Every field defaulted so [FilterHealthState] can be
 * constructed as a StateFlow seed without arguments.
 */
data class FilterStageHealth(
    val stageIndex: Int = 0,
    val stageKey: String = "",
    val action: ServiceAction = ServiceAction.REPLACE,
    /** Percent of rated life remaining, 0..100. Guaranteed finite. */
    val healthPercent: Double = 100.0,
    /** Fraction of rated life consumed. 0 = new, 1 = at rated life, >1 = overdue. */
    val usage: Double = 0.0,
    val condition: FilterCondition = FilterCondition.GOOD,
    val limitedBy: LimitedBy = LimitedBy.RUNTIME,
    /** Remaining pump-hours before the replace threshold. Guaranteed finite, >= 0. */
    val operatingHoursRemaining: Double = 0.0,
    /**
     * Remaining wall-clock days, or null when the duty cycle is not yet observable.
     * Null means the UI must show operating hours instead of a date.
     */
    val daysRemaining: Double? = null,
    /** Accumulated quality-weighted pump-hours since this stage was last serviced. */
    val weightedHours: Double = 0.0,
    /** Calendar days since this stage was last replaced (rinsing does not reset this). */
    val daysSinceService: Double = 0.0,
    /** Current wear multiplier from water quality. 1.0 = configured rated wear pace. */
    val loadFactor: Double = 1.0,
    /**
     * True when this stage is a RINSE stage whose health is bound by calendar age —
     * rinsing will not help, the media itself needs replacing.
     */
    val rinseWontHelp: Boolean = false
)

/**
 * Diagnostics for one stage's fitted model, surfaced on the Filter screen so the
 * coefficients can be read off directly for documentation.
 */
data class StageModelDiagnostics(
    val stageKey: String = "",
    val coefficients: List<Double> = emptyList(),
    val rSquared: Double = 0.0,
    val syntheticSamples: Int = 0,
    val realObservations: Int = 0,
    /** True once real serviced-stage data has displaced the synthetic prior. */
    val fittedOnRealData: Boolean = false
)

/**
 * Whole-filter health snapshot. Default-constructible so it can seed a StateFlow, which
 * matters because `combine` emits nothing until every source has emitted at least once.
 */
data class FilterHealthState(
    val stages: List<FilterStageHealth> = emptyList(),
    val runtimeSource: RuntimeSource = RuntimeSource.SIMULATED,
    /** Total cumulative pump-hours observed across both pumps. */
    val totalRuntimeHours: Double = 0.0,
    /** Observed duty cycle (runtime / wall time), or null when not yet measurable. */
    val dutyCycle: Double? = null,
    val diagnostics: List<StageModelDiagnostics> = emptyList(),
    /** True before any sample has been ingested, so the UI can show a waiting state. */
    val isWaitingForData: Boolean = true
) {
    /** The stage most in need of attention, or null before any data arrives. */
    val worstStage: FilterStageHealth?
        get() = stages.maxByOrNull { it.condition.severity * 1000 - it.healthPercent.toInt() }

    val needsAttention: Boolean
        get() = stages.any { it.condition.severity >= FilterCondition.REPLACE_SOON.severity }
}

/**
 * One recorded service event, used as a real training row once enough have accumulated.
 * Persisted, so keep the field names stable.
 */
data class WearObservation(
    val stageKey: String = "",
    /** Mean TDS over the stage's service life, normalised 0..1. */
    val tdsNorm: Double = 0.0,
    /** Mean turbidity over the stage's service life, normalised 0..1. */
    val turbidityNorm: Double = 0.0,
    /** Mean |pH - 7| over the stage's service life. */
    val phDeviation: Double = 0.0,
    /**
     * Observed wear rate: rated hours divided by the raw pump-hours the stage actually
     * survived. 1.0 means it lasted exactly its rated life under these conditions.
     */
    val observedWearRate: Double = 1.0
)
