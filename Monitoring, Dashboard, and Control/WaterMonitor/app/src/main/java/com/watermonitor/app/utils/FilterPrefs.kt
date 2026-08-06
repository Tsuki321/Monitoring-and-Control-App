package com.watermonitor.app.utils

import android.content.Context
import com.google.gson.Gson
import com.watermonitor.app.data.model.FilterSpecs
import com.watermonitor.app.data.model.WearObservation

/**
 * Persisted wear state for one stage.
 *
 * Field names are the on-disk contract — Gson serialises by field name and the app ships
 * without minification, so renaming any of these silently drops user data on upgrade.
 * Add fields, never rename them, and bump [FilterPersistedState.v] on a breaking change.
 */
data class FilterStageState(
    val key: String = "",
    /** Fraction of rated runtime consumed since the last *replacement*, net of rinses. */
    val usageRuntime: Double = 0.0,
    /** Quality-weighted pump-hours since last replacement. Display only. */
    val weightedHours: Double = 0.0,
    /** Unweighted pump-hours since last replacement. Feeds the training target. */
    val rawHours: Double = 0.0,
    /** Wall-clock millis of the last replacement. Rinsing does not move this. */
    val lastServiceWallMillis: Long = 0L,
    /** Rinses since the last replacement; a rinsed interval is not a clean training row. */
    val rinseCount: Int = 0,
    // Runtime-weighted sums of the conditions seen since the last replacement. Dividing by
    // rawHours gives the mean water quality the media actually processed — weighting by
    // runtime rather than by sample count keeps idle periods from diluting the average.
    val sumTdsNorm: Double = 0.0,
    val sumTurbidityNorm: Double = 0.0,
    val sumPhDeviation: Double = 0.0
)

/**
 * The whole persisted blob, stored as one versioned JSON string in [HYDROSENSE_PREFS].
 */
data class FilterPersistedState(
    /** Schema version. A mismatch discards the blob rather than misreading it. */
    val v: Int = CURRENT_VERSION,
    val stages: List<FilterStageState> = emptyList(),
    /**
     * Last cumulative counters read from RTDB, or null while running on simulated runtime.
     * Absolute values, not deltas — a gap while the app is closed costs no runtime.
     */
    val lastRuntimeSecondsA: Long? = null,
    val lastRuntimeSecondsB: Long? = null,
    /** Wall clock of the last accepted sample, used for duty cycle and rollback detection. */
    val lastSampleWallMillis: Long = 0L,
    /** Cumulative pump-hours this install has observed. Numerator of the duty cycle. */
    val totalRuntimeHours: Double = 0.0,
    /** Cumulative wall-clock hours over the same intervals. Denominator of the duty cycle. */
    val accumulatedWallHours: Double = 0.0,
    /** FIFO-capped real training rows, keyed by stage. */
    val observations: List<WearObservation> = emptyList()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * Reads and writes the filter wear blob.
 *
 * Deliberately dumb: no caching, no flush policy, no background threading. The repository
 * owns when to write, because a `SharedPreferences.apply()` per sensor tick rewrites the
 * entire XML file and pending writes are drained *synchronously* at `onPause`/`onStop` by
 * `QueuedWork` — a well-documented ANR source.
 *
 * Every read is wrapped so that a corrupt or hand-edited blob degrades to a fresh filter
 * rather than crashing the app on launch.
 */
object FilterPrefs {

    private const val KEY_FILTER_STATE = "filter_wear_state"

    private val gson by lazy { Gson() }

    /** Loads persisted state, or a freshly-seeded default when absent or unreadable. */
    fun load(context: Context): FilterPersistedState {
        val json = runCatching {
            prefs(context).getString(KEY_FILTER_STATE, null)
        }.getOrNull() ?: return default()

        val parsed = runCatching {
            gson.fromJson(json, FilterPersistedState::class.java)
        }.getOrNull() ?: return default()

        if (parsed.v != FilterPersistedState.CURRENT_VERSION) return default()

        // Gson happily produces nulls for absent collections regardless of Kotlin defaults,
        // so normalise rather than trusting the declared types.
        return parsed.copy(
            stages = mergeWithSpecs(parsed.stages.orEmptyList()),
            observations = parsed.observations.orEmptyList()
        )
    }

    /** Overwrites the stored blob. Uses `apply()`; callers control how often this runs. */
    fun save(context: Context, state: FilterPersistedState) {
        runCatching {
            val json = gson.toJson(state.copy(v = FilterPersistedState.CURRENT_VERSION))
            prefs(context).edit().putString(KEY_FILTER_STATE, json).apply()
        }
    }

    /** Drops all wear history, returning every stage to new. */
    fun clear(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_FILTER_STATE).apply() }
    }

    fun default(): FilterPersistedState = FilterPersistedState(stages = mergeWithSpecs(emptyList()))

    /**
     * Reconciles stored rows against [FilterSpecs] by key, so adding, removing or reordering
     * a stage in the spec table does not corrupt the stages that did not change.
     */
    private fun mergeWithSpecs(stored: List<FilterStageState>): List<FilterStageState> {
        val byKey = stored.filterNotNull().associateBy { it.key }
        return FilterSpecs.stages.map { spec ->
            byKey[spec.key] ?: FilterStageState(key = spec.key)
        }
    }

    private fun <T> List<T>?.orEmptyList(): List<T> = this ?: emptyList()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(HYDROSENSE_PREFS, Context.MODE_PRIVATE)
}
