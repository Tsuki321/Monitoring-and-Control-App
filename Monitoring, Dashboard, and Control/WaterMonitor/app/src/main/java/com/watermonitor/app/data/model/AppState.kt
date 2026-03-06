package com.watermonitor.app.data.model

data class AppState(
    val sensorData: SensorData = SensorData(),
    val pumpState: PumpState = PumpState(),
    val tankStatus: TankStatus = TankStatus(),
    val sensorStatus: SensorStatus = SensorStatus()
)
