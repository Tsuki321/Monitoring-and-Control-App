package com.watermonitor.app.ui.filter

import android.content.Context
import com.watermonitor.app.R
import com.watermonitor.app.data.model.FilterCondition
import com.watermonitor.app.data.model.FilterStageHealth
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Shared presentation for filter health, used by both the Filter screen and the Dashboard
 * card so the two never disagree about a stage's name, colour or forecast wording.
 */
object FilterFormatter {

    /** Stage display names, keyed by the stable spec key. Never key these off the index. */
    fun stageNameRes(stageKey: String): Int = when (stageKey) {
        "pumice" -> R.string.filter_stage_pumice
        "pebbles" -> R.string.filter_stage_pebbles
        "lava_rock" -> R.string.filter_stage_lava_rock
        "carbon" -> R.string.filter_stage_activated_carbon
        "sand" -> R.string.filter_stage_sand
        else -> R.string.filter_stages_title
    }

    fun conditionRes(condition: FilterCondition): Int = when (condition) {
        FilterCondition.GOOD -> R.string.filter_condition_good
        FilterCondition.MONITOR -> R.string.filter_condition_monitor
        FilterCondition.REPLACE_SOON -> R.string.filter_condition_replace_soon
        FilterCondition.REPLACE_NOW -> R.string.filter_condition_replace_now
        FilterCondition.OVERDUE -> R.string.filter_condition_overdue
    }

    fun conditionColorRes(condition: FilterCondition): Int = when (condition) {
        FilterCondition.GOOD, FilterCondition.MONITOR -> R.color.status_green
        FilterCondition.REPLACE_SOON -> R.color.status_yellow
        FilterCondition.REPLACE_NOW, FilterCondition.OVERDUE -> R.color.status_red
    }

    fun stageName(context: Context, stageKey: String): String =
        context.getString(stageNameRes(stageKey))

    fun healthLabel(context: Context, stage: FilterStageHealth): String =
        context.getString(R.string.filter_health_format, healthPercent(stage))

    /** Rounded percent, safe for a progress bar. The model already guarantees 0..100. */
    fun healthPercent(stage: FilterStageHealth): Int =
        stage.healthPercent.let { if (it.isFinite()) it.roundToInt() else 0 }.coerceIn(0, 100)

    /**
     * Remaining life as a sentence.
     *
     * Falls back to operating hours whenever the duty cycle is not yet observable — a
     * fabricated calendar date would be worse than an honest "≈480 operating hours".
     */
    fun forecastLabel(context: Context, stage: FilterStageHealth): String {
        val days = stage.daysRemaining
        if (days != null && days.isFinite()) {
            val rounded = days.roundToLong().coerceAtLeast(0L)
            val unit = if (rounded == 1L) {
                context.getString(R.string.filter_day_unit_format, rounded.toInt())
            } else {
                context.getString(R.string.filter_days_unit_format, rounded.toInt())
            }
            return context.getString(R.string.filter_days_remaining_format, unit)
        }

        val hours = stage.operatingHoursRemaining
            .let { if (it.isFinite()) it else 0.0 }
            .roundToLong()
            .coerceAtLeast(0L)
        val unit = context.getString(R.string.filter_hours_unit_format, hours.toInt())
        return context.getString(R.string.filter_hours_remaining_format, unit)
    }
}
