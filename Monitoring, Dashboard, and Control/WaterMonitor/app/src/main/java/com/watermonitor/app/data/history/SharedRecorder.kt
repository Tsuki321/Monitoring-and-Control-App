package com.watermonitor.app.data.history

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.watermonitor.app.data.model.SensorData
import com.watermonitor.app.data.model.PumpControlState
import kotlinx.coroutines.*
import java.util.UUID

/** One connected phone holds a short recorder lease; another takes over after expiry. */
object SharedRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val session: String = UUID.randomUUID().toString()
    @Volatile private var connected = false
    @Volatile private var offset = 0L
    @Volatile private var state: Map<String, Any?> = emptyMap()
    private var lastAttempt = 0L
    private var latestPump: PumpControlState? = null
    fun observeState(value: Map<String, Any?>) { state = value }
    private var started = false
    fun now() = System.currentTimeMillis() + offset
    fun initialize() {
        if (started) return
        started = true
        val db = com.watermonitor.app.data.repository.FirebaseRealtimeSensorRepository.database
        fun observe(path: String, action: (DataSnapshot) -> Unit) {
            db.getReference(path).addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) = action(snapshot)
                override fun onCancelled(error: DatabaseError) = Unit
            })
        }
        observe(".info/connected") { connected = it.getValue(Boolean::class.java) == true }
        observe(".info/serverTimeOffset") { offset = (it.value as? Number)?.toLong() ?: 0 }
        scope.launch { SharedFilterEngine.baseline }
    }
    fun onSensor(data: SensorData) {
        if (!connected || data.timestamp <= 0 || FirebaseAuth.getInstance().currentUser == null) return
        val observedTime = now()
        if (state["recorder"] != session && state.longAt("recorderUntil") > observedTime) return
        val alertChanged = state.mapAt("sample").mapAt("sensors").let { sensors ->
            sensors.isNotEmpty() && (sensors["rainDetected"] != data.leakDetected?.let { if (it) 1L else 0L } ||
                (sensors["tankWarning"] as? Number)?.toInt() != data.tankWarning)
        }
        if (observedTime - lastAttempt < 60_000 && !alertChanged) return
        lastAttempt = observedTime
        scope.launch {
            SharedFilterEngine.baseline
            val sensors = linkedMapOf<String, Any?>("ph" to data.ph, "tds" to data.tds, "turbidity" to data.turbidity,
                "runtimeA" to data.runtimeASeconds, "runtimeB" to data.runtimeBSeconds,
                "tankDistanceMm" to data.tankDistanceMm, "tankLevel" to data.tankLevel?.toDouble(),
                "tankWarning" to data.tankWarning, "rainDetected" to data.leakDetected?.let { if (it) 1 else 0 })
            val fingerprint = HistoryRepository.hash(HistoryRepository.gson.toJson(sensors))
            val time = now(); val id = UUID.randomUUID().toString()
            transact({ old ->
                if (old["recorder"] != session && old.longAt("recorderUntil") > time) return@transact null
                SharedFilterEngine.advance(old, mapOf("id" to id, "time" to time, "sensors" to sensors)).toMutableMap().apply {
                    put("recorder", session); put("recorderUntil", time + 150_000); put("fingerprint", fingerprint)
                }
            }, { if (it == null) latestPump?.let(::onPump) })
        }
    }
    fun onPump(pump: PumpControlState) {
        latestPump = pump
        if (!connected || FirebaseAuth.getInstance().currentUser == null) return
        val value = mapOf("pumpA" to pump.actualPumpA, "pumpB" to pump.actualPumpB,
            "commandedPumpA" to pump.commandedPumpA, "commandedPumpB" to pump.commandedPumpB, "autoMode" to pump.autoMode)
        val fingerprint = HistoryRepository.hash(HistoryRepository.gson.toJson(value))
        val time = now(); val id = UUID.randomUUID().toString()
        transact({ old ->
            if (old["recorder"] != session || old.mapAt("pump")["fingerprint"] == fingerprint) null
            else old.toMutableMap().apply { put("pump", mapOf("id" to id, "time" to time, "value" to value, "fingerprint" to fingerprint)) }
        })
    }
    fun service(key: String, action: String, revision: Long, reason: String, condition: String, complete: (String?) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser ?: return complete("Sign in first")
        if (!connected) return complete("Reconnect before recording maintenance")
        val time = now(); val id = UUID.randomUUID().toString()
        transact({ old -> SharedFilterEngine.service(old, key, action, revision, reason, condition, user.uid, id, time) }, complete)
    }
    private fun transact(update: (Map<String, Any?>) -> Map<String, Any?>?, complete: ((String?) -> Unit)? = null) {
        HistoryRepository.root.child("current").runTransaction(object : Transaction.Handler {
            override fun doTransaction(current: MutableData): Transaction.Result {
                @Suppress("UNCHECKED_CAST") val old = current.value as? Map<String, Any?> ?: emptyMap()
                val next = update(old) ?: return Transaction.abort()
                current.value = next
                return Transaction.success(current)
            }
            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null) HistoryRepository.status.value = "Shared recorder unavailable: ${error.message}"
                complete?.invoke(error?.message ?: if (!committed) "Filter changed on another phone. Refresh and try again." else null)
            }
        }, false)
    }
}
