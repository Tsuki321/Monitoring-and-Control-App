package com.watermonitor.app.data.model

enum class WaterSafetyLevel {
    UNKNOWN,
    SAFE,
    CAUTION,
    UNSAFE
}

enum class PhQuality(val safetyLevel: WaterSafetyLevel) {
    ACIDIC(WaterSafetyLevel.UNSAFE),
    ACCEPTABLE(WaterSafetyLevel.SAFE),
    ALKALINE(WaterSafetyLevel.CAUTION)
}

enum class TdsQuality(val safetyLevel: WaterSafetyLevel) {
    VERY_LOW(WaterSafetyLevel.CAUTION),
    EXCELLENT(WaterSafetyLevel.SAFE),
    GOOD(WaterSafetyLevel.CAUTION),
    POOR(WaterSafetyLevel.UNSAFE)
}

enum class TurbidityQuality(val safetyLevel: WaterSafetyLevel) {
    CLEAR(WaterSafetyLevel.SAFE),
    SLIGHTLY_TURBID(WaterSafetyLevel.CAUTION),
    TURBID(WaterSafetyLevel.UNSAFE)
}

data class WaterQualityAssessment(
    val phQuality: PhQuality,
    val tdsQuality: TdsQuality,
    val turbidityQuality: TurbidityQuality
) {
    val overallSafety: WaterSafetyLevel
        get() {
            val levels = listOf(
                phQuality.safetyLevel,
                tdsQuality.safetyLevel,
                turbidityQuality.safetyLevel
            )
            return when {
                WaterSafetyLevel.UNSAFE in levels -> WaterSafetyLevel.UNSAFE
                WaterSafetyLevel.CAUTION in levels -> WaterSafetyLevel.CAUTION
                else -> WaterSafetyLevel.SAFE
            }
        }
}

object WaterQualityEvaluator {

    fun evaluate(data: SensorData): WaterQualityAssessment {
        val phQuality = when {
            data.ph < 6.5 -> PhQuality.ACIDIC
            data.ph > 8.5 -> PhQuality.ALKALINE
            else -> PhQuality.ACCEPTABLE
        }

        val tdsQuality = when {
            data.tds < 50 -> TdsQuality.VERY_LOW
            data.tds > 500 -> TdsQuality.POOR
            data.tds > 300 -> TdsQuality.GOOD
            else -> TdsQuality.EXCELLENT
        }

        val turbidityQuality = when {
            data.turbidity > 4.0 -> TurbidityQuality.TURBID
            data.turbidity > 1.5 -> TurbidityQuality.SLIGHTLY_TURBID
            else -> TurbidityQuality.CLEAR
        }

        return WaterQualityAssessment(phQuality, tdsQuality, turbidityQuality)
    }
}
