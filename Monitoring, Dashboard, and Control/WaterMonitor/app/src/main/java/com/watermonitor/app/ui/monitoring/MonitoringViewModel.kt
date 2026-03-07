package com.watermonitor.app.ui.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermonitor.app.R
import com.watermonitor.app.data.model.SensorData
import com.watermonitor.app.data.repository.MockSensorRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class SensorCardUiState(
    val value: Double = 0.0,
    val statusLabelRes: Int = R.string.status_neutral,
    val statusColorRes: Int = 0
)

data class MonitoringUiState(
    val sensorData: SensorData = SensorData(),
    val phStatus: SensorCardUiState = SensorCardUiState(),
    val tdsStatus: SensorCardUiState = SensorCardUiState(),
    val turbidityStatus: SensorCardUiState = SensorCardUiState()
)

class MonitoringViewModel : ViewModel() {

    val uiState: StateFlow<MonitoringUiState> =
        MockSensorRepository.sensorDataFlow
            .map { data -> buildUiState(data) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = MonitoringUiState()
            )

    private fun buildUiState(data: SensorData): MonitoringUiState {
        return MonitoringUiState(
            sensorData = data,
            phStatus = phCardState(data.ph),
            tdsStatus = tdsCardState(data.tds),
            turbidityStatus = turbidityCardState(data.turbidity)
        )
    }

    private fun phCardState(ph: Double): SensorCardUiState {
        val (labelRes, color) = when {
            ph < 6.5 -> Pair(R.string.status_acidic, android.graphics.Color.parseColor("#E74C3C"))
            ph > 7.5 -> Pair(R.string.status_alkaline, android.graphics.Color.parseColor("#F39C12"))
            else -> Pair(R.string.status_neutral, android.graphics.Color.parseColor("#2ECC71"))
        }
        return SensorCardUiState(value = ph, statusLabelRes = labelRes, statusColorRes = color)
    }

    private fun tdsCardState(tds: Int): SensorCardUiState {
        val (labelRes, color) = when {
            tds < 50 -> Pair(R.string.status_very_low, android.graphics.Color.parseColor("#F39C12"))
            tds > 500 -> Pair(R.string.status_poor, android.graphics.Color.parseColor("#E74C3C"))
            tds > 300 -> Pair(R.string.status_good, android.graphics.Color.parseColor("#F39C12"))
            else -> Pair(R.string.status_excellent, android.graphics.Color.parseColor("#2ECC71"))
        }
        return SensorCardUiState(value = tds.toDouble(), statusLabelRes = labelRes, statusColorRes = color)
    }

    private fun turbidityCardState(turbidity: Double): SensorCardUiState {
        val (labelRes, color) = when {
            turbidity > 4.0 -> Pair(R.string.status_turbid, android.graphics.Color.parseColor("#E74C3C"))
            turbidity > 1.5 -> Pair(R.string.status_slightly_turbid, android.graphics.Color.parseColor("#F39C12"))
            else -> Pair(R.string.status_clear, android.graphics.Color.parseColor("#2ECC71"))
        }
        return SensorCardUiState(value = turbidity, statusLabelRes = labelRes, statusColorRes = color)
    }
}
