package com.watermonitor.app.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.watermonitor.app.data.model.PumpControlState
import com.watermonitor.app.data.model.SensorData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.shareIn

@OptIn(ExperimentalCoroutinesApi::class)
object FirebaseRealtimeSensorRepository {

    private const val TAG = "HydroSenseRTDB"

    /**
     * Must match Firebase Console → Realtime Database → URL (include region suffix if shown).
     * Example: https://database-for-hydrosense-default-rtdb.firebaseio.com
     * or: https://database-for-hydrosense-default-rtdb.asia-southeast1.firebasedatabase.app
     */
    private const val DATABASE_URL =
        "https://database-for-hydrosense-default-rtdb.asia-southeast1.firebasedatabase.app"

    /**
     * Single long-lived scope so that SharedFlow collectors share the same
     * underlying RTDB listener instead of each re-attaching their own.
     */
    private val repoScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    private val database: FirebaseDatabase by lazy {
        FirebaseDatabase.getInstance(DATABASE_URL).apply {
            // Only enable verbose Firebase logging in debug builds
            if (com.watermonitor.app.BuildConfig.DEBUG) {
                setLogLevel(com.google.firebase.database.Logger.Level.DEBUG)
            }
        }
    }

    private val sensorsRef by lazy { database.getReference("sensors") }
    private val statusRef by lazy { database.getReference("status") }
    private val controlPumpARef by lazy { database.getReference("control/pumpA") }
    private val controlPumpBRef by lazy { database.getReference("control/pumpB") }
    private val controlAutoRef by lazy { database.getReference("control/auto") }

    /**
     * SharedFlow so multiple ViewModels (Dashboard, Control, Monitoring) share a
     * single underlying RTDB listener instead of each re-attaching their own.
     * WhileSubscribed(5_000) keeps the listener alive briefly across config changes.
     */
    val sensorDataFlow: Flow<SensorData> = authStateFlow().flatMapLatest { signedIn ->
        if (!signedIn) {
            Log.w(TAG, "No Firebase user; RTDB read skipped (rules require auth != null)")
            flowOf(SensorData())
        } else {
            sensorsSnapshotFlow()
        }
    }.shareIn(repoScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * Live pump control state: combines actual relay states from `/status`
     * (written by the ESP32) with the mode flag from `/control/auto` (written
     * by the app). Both are gated behind Firebase auth.
     *
     * SharedFlow so Dashboard + Control ViewModels share a single RTDB listener.
     */
    val pumpControlFlow: Flow<PumpControlState> = authStateFlow().flatMapLatest { signedIn ->
        if (!signedIn) {
            Log.w(TAG, "No Firebase user; pump control flow skipped")
            flowOf(PumpControlState())
        } else {
            combine(
                pumpStatusSnapshotFlow(),
                autoModeSnapshotFlow(),
                controlPumpASnapshotFlow(),
                controlPumpBSnapshotFlow()
            ) { pumpStatus, auto, cmdA, cmdB ->
                PumpControlState(
                    commandedPumpA = cmdA,
                    commandedPumpB = cmdB,
                    actualPumpA = pumpStatus.first,
                    actualPumpB = pumpStatus.second,
                    autoMode = auto
                )
            }
        }
    }.shareIn(repoScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    // ── Writers: app → ESP32 via /control ──────────────────────────────────

    fun setPumpA(command: Boolean) {
        controlPumpARef.setValue(if (command) 1 else 0) { error, _ ->
            if (error != null) Log.e(TAG, "setPumpA failed: ${error.message}")
            else Log.d(TAG, "Wrote /control/pumpA = ${if (command) 1 else 0}")
        }
    }

    fun setPumpB(command: Boolean) {
        controlPumpBRef.setValue(if (command) 1 else 0) { error, _ ->
            if (error != null) Log.e(TAG, "setPumpB failed: ${error.message}")
            else Log.d(TAG, "Wrote /control/pumpB = ${if (command) 1 else 0}")
        }
    }

    fun setAutoMode(enabled: Boolean) {
        controlAutoRef.setValue(if (enabled) 1 else 0) { error, _ ->
            if (error != null) Log.e(TAG, "setAutoMode failed: ${error.message}")
            else Log.d(TAG, "Wrote /control/auto = ${if (enabled) 1 else 0}")
        }
    }

    private fun authStateFlow(): Flow<Boolean> = callbackFlow {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener {
            val ok = auth.currentUser != null
            Log.d(TAG, "Auth state: signedIn=$ok uid=${auth.currentUser?.uid}")
            trySend(ok)
        }
        auth.addAuthStateListener(listener)
        listener.onAuthStateChanged(auth)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    private fun sensorsSnapshotFlow(): Flow<SensorData> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val data = snapshot.toSensorData()
                Log.d(TAG, "onDataChange exists=${snapshot.exists()} ph=${data.ph} tds=${data.tds} turbidity=${data.turbidity} tankDistanceMm=${data.tankDistanceMm} tankLevel=${data.tankLevel}")
                trySend(data)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "onCancelled code=${error.code} message=${error.message}")
                close(error.toException())
            }
        }
        Log.d(TAG, "Attaching listener to $DATABASE_URL/sensors")
        sensorsRef.addValueEventListener(listener)
        awaitClose {
            Log.d(TAG, "Removing RTDB listener")
            sensorsRef.removeEventListener(listener)
        }
    }

    private fun pumpStatusSnapshotFlow(): Flow<Pair<Boolean, Boolean>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val pumpA = snapshot.child("pumpA").asBool(false)
                val pumpB = snapshot.child("pumpB").asBool(false)
                Log.d(TAG, "onDataChange /status pumpA=$pumpA pumpB=$pumpB")
                trySend(Pair(pumpA, pumpB))
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "/status cancelled code=${error.code} message=${error.message}")
                close(error.toException())
            }
        }
        Log.d(TAG, "Attaching listener to $DATABASE_URL/status")
        statusRef.addValueEventListener(listener)
        awaitClose {
            Log.d(TAG, "Removing /status listener")
            statusRef.removeEventListener(listener)
        }
    }

    private fun autoModeSnapshotFlow(): Flow<Boolean> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val auto = snapshot.asBool(true)
                Log.d(TAG, "onDataChange /control/auto=$auto")
                trySend(auto)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "/control/auto cancelled code=${error.code} message=${error.message}")
                close(error.toException())
            }
        }
        Log.d(TAG, "Attaching listener to $DATABASE_URL/control/auto")
        controlAutoRef.addValueEventListener(listener)
        awaitClose {
            Log.d(TAG, "Removing /control/auto listener")
            controlAutoRef.removeEventListener(listener)
        }
    }

    private fun controlPumpASnapshotFlow(): Flow<Boolean> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val cmd = snapshot.asBool(false)
                Log.d(TAG, "onDataChange /control/pumpA=$cmd")
                trySend(cmd)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "/control/pumpA cancelled code=${error.code} message=${error.message}")
                close(error.toException())
            }
        }
        Log.d(TAG, "Attaching listener to $DATABASE_URL/control/pumpA")
        controlPumpARef.addValueEventListener(listener)
        awaitClose {
            Log.d(TAG, "Removing /control/pumpA listener")
            controlPumpARef.removeEventListener(listener)
        }
    }

    private fun controlPumpBSnapshotFlow(): Flow<Boolean> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val cmd = snapshot.asBool(false)
                Log.d(TAG, "onDataChange /control/pumpB=$cmd")
                trySend(cmd)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "/control/pumpB cancelled code=${error.code} message=${error.message}")
                close(error.toException())
            }
        }
        Log.d(TAG, "Attaching listener to $DATABASE_URL/control/pumpB")
        controlPumpBRef.addValueEventListener(listener)
        awaitClose {
            Log.d(TAG, "Removing /control/pumpB listener")
            controlPumpBRef.removeEventListener(listener)
        }
    }

    private fun DataSnapshot.toSensorData(): SensorData {
        if (!exists()) {
            Log.w(TAG, "Snapshot at /sensors does not exist")
            return SensorData()
        }

        @Suppress("UNCHECKED_CAST")
        val map = getValue() as? Map<String, Any?>
        if (map != null) {
            return SensorData(
                ph = map.parseDouble("ph", 7.0),
                tds = map.parseInt("tds", 150),
                turbidity = map.parseDouble("turbidity", 1.5),
                tankDistanceMm = map.parseIntOrNull("tankDistanceMm"),
                tankLevel = map.parseDoubleOrNull("tankLevel")?.toFloat(),
                timestamp = System.currentTimeMillis()
            )
        }

        return SensorData(
            ph = child("ph").asDouble(7.0),
            tds = child("tds").asInt(150),
            turbidity = child("turbidity").asDouble(1.5),
            tankDistanceMm = child("tankDistanceMm").asIntOrNull(),
            tankLevel = child("tankLevel").asDoubleOrNull()?.toFloat(),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun Map<String, Any?>.parseDouble(key: String, default: Double): Double {
        return when (val value = this[key]) {
            null -> default
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: default
            else -> default
        }
    }

    private fun Map<String, Any?>.parseInt(key: String, default: Int): Int {
        return when (val value = this[key]) {
            null -> default
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }

    private fun Map<String, Any?>.parseDoubleOrNull(key: String): Double? {
        return when (val value = this[key]) {
            null -> null
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun Map<String, Any?>.parseIntOrNull(key: String): Int? {
        return when (val value = this[key]) {
            null -> null
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
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

    private fun DataSnapshot.asDoubleOrNull(): Double? {
        val value = getValue() ?: return null
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun DataSnapshot.asIntOrNull(): Int? {
        val value = getValue() ?: return null
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun DataSnapshot.asBool(default: Boolean): Boolean {
        val value = getValue() ?: return default
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value == "1" || value.equals("true", ignoreCase = true)
            else -> default
        }
    }
}