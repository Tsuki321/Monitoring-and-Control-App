package com.watermonitor.app.ui.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermonitor.app.data.model.SensorData
import com.watermonitor.app.data.repository.MockSensorRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class SensorCardUiState(
    val value: Double = 0.0,
    val statusLabel: String = "",
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
        val (label, color) = when {
            ph < 6.5 -> Pair("Acidic", android.graphics.Color.parseColor("#E74C3C"))
            ph > 7.5 -> Pair("Alkaline", android.graphics.Color.parseColor("#F39C12"))
            else -> Pair("Neutral", android.graphics.Color.parseColor("#2ECC71"))
        }
        return SensorCardUiState(value = ph, statusLabel = label, statusColorRes = color)
    }

    private fun tdsCardState(tds: Int): SensorCardUiState {
        val (label, color) = when {
            tds < 50 -> Pair("Very Low", android.graphics.Color.parseColor("#F39C12"))
            tds > 500 -> Pair("Poor", android.graphics.Color.parseColor("#E74C3C"))
            tds > 300 -> Pair("Good", android.graphics.Color.parseColor("#F39C12"))
            else -> Pair("Excellent", android.graphics.Color.parseColor("#2ECC71"))
        }
        return SensorCardUiState(value = tds.toDouble(), statusLabel = label, statusColorRes = color)
    }

    private fun turbidityCardState(turbidity: Double): SensorCardUiState {
        val (label, color) = when {
            turbidity > 4.0 -> Pair("Turbid", android.graphics.Color.parseColor("#E74C3C"))
            turbidity > 1.5 -> Pair("Slightly Turbid", android.graphics.Color.parseColor("#F39C12"))
            else -> Pair("Clear", android.graphics.Color.parseColor("#2ECC71"))
        }
        return SensorCardUiState(value = turbidity, statusLabel = label, statusColorRes = color)
    }
}
