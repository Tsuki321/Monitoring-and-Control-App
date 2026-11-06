package com.watermonitor.app.notifications

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DatabaseError
import com.watermonitor.app.data.repository.FirebaseRealtimeSensorRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.onStart

internal data class SafetyReading(val tank: TankReading, val leakDetected: Boolean?)

internal object TankTelemetry {
    fun readings(): Flow<SafetyReading?> {
        val database = FirebaseRealtimeSensorRepository.database
        return combine(
            database.getReference("sensors").snapshots().map {
                it?.let { snapshot ->
                    SafetyReading(
                        TankReading.parse(snapshot.child("tankLevel").value, snapshot.child("tankWarning").value),
                        LeakAlertPolicy.parse(snapshot.child("rainDetected").value)
                    )
                }
            },
            database.getReference(".info/connected").snapshots().map { it?.value == true }
        ) { reading, connected -> if (connected) reading else null }
    }

    private fun DatabaseReference.snapshots(): Flow<DataSnapshot?> = callbackFlow<DataSnapshot?> {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { trySend(snapshot) }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        addValueEventListener(listener)
        awaitClose { removeEventListener(listener) }
    }.retryWhen { cause, _ ->
        emit(null)
        Log.w("HydroSenseTank", "Tank listener unavailable; retrying", cause)
        delay(30_000)
        true
    }.onStart { emit(null) }
}
