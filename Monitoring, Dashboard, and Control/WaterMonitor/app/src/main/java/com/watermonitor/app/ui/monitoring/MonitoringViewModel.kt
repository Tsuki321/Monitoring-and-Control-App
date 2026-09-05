package com.watermonitor.app.ui.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermonitor.app.R
import com.watermonitor.app.data.model.PhQuality
import com.watermonitor.app.data.model.SensorData
import com.watermonitor.app.data.model.TdsQuality
import com.watermonitor.app.data.model.TurbidityQuality
import com.watermonitor.app.data.model.WaterQualityEvaluator
import com.watermonitor.app.data.model.WaterSafetyLevel
import com.watermonitor.app.data.repository.FirebaseRealtimeSensorRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class SensorCardUiState(
    val value: Double = 0.0,
    val statusLabelRes: Int = R.string.status_neutral,
    val statusColorRes: Int = 0,
    val cloudinessPercent: Int = 0
)

data class MonitoringUiState(
    val sensorData: SensorData = SensorData(),
    val phStatus: SensorCardUiState = SensorCardUiState(),
    val tdsStatus: SensorCardUiState = SensorCardUiState(),
    val turbidityStatus: SensorCardUiState = SensorCardUiState()
)

class MonitoringViewModel : ViewModel() {

    val uiState: StateFlow<MonitoringUiState> =
        FirebaseRealtimeSensorRepository.sensorDataFlow
            .map { data -> buildUiState(data) }
            .catch { emit(buildUiState(SensorData())) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = buildUiState(SensorData())
            )

    private fun buildUiState(data: SensorData): MonitoringUiState {
        val assessment = WaterQualityEvaluator.evaluate(data)
        return MonitoringUiState(
            sensorData = data,
            phStatus = phCardState(data.ph, assessment.phQuality),
            tdsStatus = tdsCardState(data.tds, assessment.tdsQuality),
            turbidityStatus = turbidityCardState(data.turbidity, assessment.turbidityQuality)
        )
    }

    private fun phCardState(ph: Double, quality: PhQuality): SensorCardUiState {
        val labelRes = when (quality) {
            PhQuality.ACIDIC -> R.string.status_acidic
            PhQuality.ACCEPTABLE -> R.string.status_neutral
            PhQuality.ALKALINE -> R.string.status_alkaline
        }
        return SensorCardUiState(
            value = ph,
            statusLabelRes = labelRes,
            statusColorRes = statusColor(quality.safetyLevel)
        )
    }

    private fun tdsCardState(tds: Int, quality: TdsQuality): SensorCardUiState {
        val labelRes = when (quality) {
            TdsQuality.VERY_LOW -> R.string.status_very_low
            TdsQuality.EXCELLENT -> R.string.status_excellent
            TdsQuality.GOOD -> R.string.status_good
            TdsQuality.POOR -> R.string.status_poor
        }
        return SensorCardUiState(
            value = tds.toDouble(),
            statusLabelRes = labelRes,
            statusColorRes = statusColor(quality.safetyLevel)
        )
    }

    private fun turbidityCardState(
        turbidity: Double,
        quality: TurbidityQuality
    ): SensorCardUiState {
        val cloudiness = (turbidity / TURBIDITY_FULL_SCALE_NTU * 100)
            .toInt()
            .coerceIn(0, 100)
        return SensorCardUiState(
            value = turbidity,
            statusColorRes = statusColor(quality.safetyLevel),
            cloudinessPercent = cloudiness
        )
    }

    private fun statusColor(safetyLevel: WaterSafetyLevel): Int = when (safetyLevel) {
        WaterSafetyLevel.UNKNOWN -> STATUS_GREY
        WaterSafetyLevel.SAFE -> STATUS_GREEN
        WaterSafetyLevel.CAUTION -> STATUS_YELLOW
        WaterSafetyLevel.UNSAFE -> STATUS_RED
    }

    private companion object {
        // Mirror the status palette in colors.xml so the animated transitions stay consistent.
        val STATUS_GREEN = android.graphics.Color.parseColor("#10B981")
        val STATUS_YELLOW = android.graphics.Color.parseColor("#F59E0B")
        val STATUS_RED = android.graphics.Color.parseColor("#EF4444")
        val STATUS_GREY = android.graphics.Color.parseColor("#94A3B8")
        const val TURBIDITY_FULL_SCALE_NTU = 5.0
    }
}
