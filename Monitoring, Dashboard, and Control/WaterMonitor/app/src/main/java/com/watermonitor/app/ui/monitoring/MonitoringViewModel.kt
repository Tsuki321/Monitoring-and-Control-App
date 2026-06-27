package com.watermonitor.app.ui.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermonitor.app.R
import com.watermonitor.app.data.model.SensorData
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
        // EPA/WHO drinking water acceptable range is 6.5–8.5.
        val (labelRes, color) = when {
            ph < 6.5 -> Pair(R.string.status_acidic, STATUS_RED)
            ph > 8.5 -> Pair(R.string.status_alkaline, STATUS_YELLOW)
            else -> Pair(R.string.status_neutral, STATUS_GREEN)
        }
        return SensorCardUiState(value = ph, statusLabelRes = labelRes, statusColorRes = color)
    }

    private fun tdsCardState(tds: Int): SensorCardUiState {
        val (labelRes, color) = when {
            tds < 50 -> Pair(R.string.status_very_low, STATUS_YELLOW)
            tds > 500 -> Pair(R.string.status_poor, STATUS_RED)
            tds > 300 -> Pair(R.string.status_good, STATUS_YELLOW)
            else -> Pair(R.string.status_excellent, STATUS_GREEN)
        }
        return SensorCardUiState(value = tds.toDouble(), statusLabelRes = labelRes, statusColorRes = color)
    }

    private fun turbidityCardState(turbidity: Double): SensorCardUiState {
        val (labelRes, color) = when {
            turbidity > 4.0 -> Pair(R.string.status_turbid, STATUS_RED)
            turbidity > 1.5 -> Pair(R.string.status_slightly_turbid, STATUS_YELLOW)
            else -> Pair(R.string.status_clear, STATUS_GREEN)
        }
        val cloudiness = (turbidity / TURBIDITY_FULL_SCALE_NTU * 100)
            .toInt()
            .coerceIn(0, 100)
        return SensorCardUiState(
            value = turbidity,
            statusLabelRes = labelRes,
            statusColorRes = color,
            cloudinessPercent = cloudiness
        )
    }

    private companion object {
        // Mirror the refined palette in colors.xml so status text matches the rest of the UI
        val STATUS_GREEN = android.graphics.Color.parseColor("#10B981")
        val STATUS_YELLOW = android.graphics.Color.parseColor("#F59E0B")
        val STATUS_RED = android.graphics.Color.parseColor("#EF4444")
        const val TURBIDITY_FULL_SCALE_NTU = 5.0
    }
}
