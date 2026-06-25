package com.watermonitor.app.data.repository

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.watermonitor.app.data.model.SensorData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object FirebaseRealtimeSensorRepository {

    private val sensorsRef = FirebaseDatabase.getInstance().getReference("sensors")

    val sensorDataFlow: Flow<SensorData> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot.toSensorData())
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        sensorsRef.addValueEventListener(listener)
        awaitClose { sensorsRef.removeEventListener(listener) }
    }

    private fun DataSnapshot.toSensorData(): SensorData {
        if (!exists()) return SensorData()
        return SensorData(
            ph = child("ph").asDouble(7.0),
            tds = child("tds").asInt(150),
            turbidity = child("turbidity").asDouble(1.5),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun DataSnapshot.asDouble(default: Double): Double {
        val value = getValue() ?: return default
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: default
            else -> default
        }
    }

    private fun DataSnapshot.asInt(default: Int): Int {
        val value = getValue() ?: return default
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }
}