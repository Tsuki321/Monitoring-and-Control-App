package com.watermonitor.app.data.repository

import com.watermonitor.app.data.model.PumpState
import com.watermonitor.app.data.model.SensorData
import com.watermonitor.app.data.model.SensorStatus
import com.watermonitor.app.data.model.TankStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

object MockSensorRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _pumpState = MutableStateFlow(PumpState())
    val pumpState: StateFlow<PumpState> = _pumpState.asStateFlow()

    private val _tankStatus = MutableStateFlow(TankStatus(fillPercent = 65f))
    val tankStatus: StateFlow<TankStatus> = _tankStatus.asStateFlow()

    private val _sensorStatus = MutableStateFlow(SensorStatus())
    val sensorStatus: StateFlow<SensorStatus> = _sensorStatus.asStateFlow()

    init {
        // Tank fill simulation runs independently — always active regardless of which screen is shown
        scope.launch {
            while (true) {
                delay(3_000)
                val current = _tankStatus.value.fillPercent
                val delta = Random.nextFloat() * 0.6f - 0.3f
                _tankStatus.value = _tankStatus.value.copy(
                    fillPercent = (current + delta).coerceIn(10f, 100f)
                )
            }
        }
    }

    /** Emits simulated sensor data every 3 seconds, oscillating naturally */
    val sensorDataFlow: Flow<SensorData> = flow {
        var tick = 0
        while (true) {
            val time = tick.toDouble()

            // pH oscillates 6.8 – 7.8 with gentle sine wave + small random jitter
            val ph = 7.3 + 0.5 * sin(time * 0.18) + Random.nextDouble(-0.05, 0.05)

            // TDS oscillates 120 – 200 ppm
            val tds = (160 + 40 * sin(time * 0.12) + Random.nextDouble(-5.0, 5.0)).toInt()
                .coerceIn(120, 200)

            // Turbidity 0.5 – 3.5 NTU
            val turbidity = 2.0 + 1.5 * sin(time * 0.22) + Random.nextDouble(-0.15, 0.15)

            emit(
                SensorData(
                    ph = ph.coerceIn(6.0, 9.0),
                    tds = tds,
                    turbidity = turbidity.coerceIn(0.2, 4.5),
                    timestamp = System.currentTimeMillis()
                )
            )

            tick++
            delay(3_000)
        }
    }

    fun togglePumpA() {
        _pumpState.value = _pumpState.value.copy(pumpA = !_pumpState.value.pumpA)
    }

    fun togglePumpB() {
        _pumpState.value = _pumpState.value.copy(pumpB = !_pumpState.value.pumpB)
    }

    fun toggleValveMain() {
        _pumpState.value = _pumpState.value.copy(valveMain = !_pumpState.value.valveMain)
    }

    fun toggleValveBypass() {
        _pumpState.value = _pumpState.value.copy(valveBypass = !_pumpState.value.valveBypass)
    }
}
