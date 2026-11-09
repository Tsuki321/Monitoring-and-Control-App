package com.watermonitor.app.data.history

import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.gson.Gson
import com.watermonitor.app.data.repository.FilterHealthRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Local journal of canonical source records. Only closed months are sent to Firestore. */
object HistoryRepository {
    const val SYSTEM_ID = "longos"
    const val DATABASE_URL = "https://database-for-hydrosense-default-rtdb.asia-southeast1.firebasedatabase.app"
    val gson = Gson()
    val status = MutableStateFlow("Connecting to shared history…")
    val official = MutableStateFlow<Map<String, Any?>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var context: Context? = null
    private val listeners = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
    private var lastMinute = -1L
    private var lastAlerts: String? = null
    val root: DatabaseReference get() = FirebaseDatabase.getInstance(DATABASE_URL)
        .getReference("systems/$SYSTEM_ID")

    @Synchronized fun initialize(app: Context) {
        if (context != null) return
        context = app.applicationContext
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork("monthly-history", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MonthlyUploadWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints).build())
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            listeners.forEach { (ref, listener) -> ref.removeEventListener(listener) }
            listeners.clear()
            official.value = emptyMap()
            lastMinute = -1
            lastAlerts = null
            FilterHealthRepository.clearSharedState()
            if (auth.currentUser == null) {
                status.value = "Sign in to synchronize history"
            } else {
                status.value = "Waiting for shared telemetry"
                listen("current") { value ->
                    val sample = value.mapAt("sample")
                    val time = sample.longAt("time")
                    if (time <= 0) return@listen
                    val stages = value.mapAt("stages")
                    FilterHealthRepository.applySharedState(value)
                    val minute = time / 60_000
                    if (minute != lastMinute) {
                        lastMinute = minute
                        record("sample-${sample["id"]}", "sample", time,
                            mapOf("sample" to sample, "stages" to stages,
                                "runtimeHours" to value["runtimeHours"], "wallHours" to value["wallHours"]))
                    }
                    val sensors = sample.mapAt("sensors")
                    val alerts = "${sensors["rainDetected"]}:${sensors["tankWarning"]}"
                    if (alerts != lastAlerts) {
                        lastAlerts = alerts
                        record("alert-${sample["id"]}", "alert", time, sensors)
                    }
                    status.value = if (System.currentTimeMillis() - time > 180_000)
                        "Last shared reading is stale; missing periods appear as gaps"
                    else "Recording locally • monthly archive enabled"
                }
                listen("latestStatus") { value ->
                    if (value.longAt("time") > 0) record("status-${value["id"]}", "pump", value.longAt("time"), value)
                }
                listen("latestControl") { value ->
                    if (value.longAt("time") > 0) record("control-${value["id"]}", "control", value.longAt("time"), value)
                }
                listen("maintenance") { values ->
                    values.forEach { (id, raw) ->
                        @Suppress("UNCHECKED_CAST") val event = raw as? Map<String, Any?> ?: return@forEach
                        if (event.longAt("time") > 0) record("maintenance-$id", "maintenance", event.longAt("time"), event)
                    }
                }
                listen("official") { value ->
                    official.value = value
                    FilterHealthRepository.refreshOfficial()
                }
                uploadClosedMonths()
            }
        }
    }

    private fun listen(path: String, receive: (Map<String, Any?>) -> Unit) {
        val ref = root.child(path)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                @Suppress("UNCHECKED_CAST") val value = snapshot.value as? Map<String, Any?> ?: emptyMap()
                runCatching { receive(value) }.onFailure { Log.w("History", "Invalid $path", it) }
            }
            override fun onCancelled(error: DatabaseError) { status.value = "History sync unavailable: ${error.message}" }
        }
        ref.addValueEventListener(listener)
        listeners += ref to listener
    }

    fun record(id: String, kind: String, time: Long, payload: Map<String, Any?>) {
        val app = context ?: return
        scope.launch {
            runCatching { HistoryDatabase.get(app).history().insert(
                HistoryRecord(id, ArchiveCalendar.month(time), time, kind, gson.toJson(payload)))
            }.onFailure { status.value = "Unable to save local history: ${it.localizedMessage}" }
        }
    }

    fun command(action: String, enabled: Boolean, error: String?) {
        record(UUID.randomUUID().toString(), "command", System.currentTimeMillis(),
            mapOf("action" to action, "enabled" to enabled, "result" to (error ?: "accepted"),
                "actor" to FirebaseAuth.getInstance().currentUser?.uid))
    }

    /** Server checks the current revision before applying maintenance, never last-write-wins. */
    fun service(stageKey: String, action: String, revision: Long, reason: String, condition: String,
                complete: (String?) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser ?: return complete("Sign in first")
        val id = UUID.randomUUID().toString()
        root.child("requests/$id").setValue(mapOf("stageKey" to stageKey, "action" to action,
            "expectedRevision" to revision, "reason" to reason, "condition" to condition,
            "actor" to user.uid, "requestedAt" to ServerValue.TIMESTAMP)) { error, _ ->
            if (error != null) complete(error.message) else {
                val ref = root.child("results/$id")
                var finished = false
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (!snapshot.exists()) return
                        if (finished) return
                        finished = true
                        ref.removeEventListener(this)
                        complete(if (snapshot.child("ok").getValue(Boolean::class.java) == true) null
                            else snapshot.child("message").getValue(String::class.java) ?: "Maintenance was not applied")
                    }
                    override fun onCancelled(error: DatabaseError) {
                        ref.removeEventListener(this)
                        if (!finished) { finished = true; complete(error.message) }
                    }
                }
                ref.addValueEventListener(listener)
                scope.launch(Dispatchers.Main) {
                    delay(30_000)
                    ref.removeEventListener(listener)
                    if (!finished) {
                        finished = true
                        complete("Still waiting for the server. Check the maintenance history before trying again.")
                    }
                }
            }
        }
    }

    fun uploadClosedMonths() {
        val app = context ?: return
        WorkManager.getInstance(app).enqueueUniqueWork("upload-closed-months", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MonthlyUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.mapAt(key: String): Map<String, Any?> = this[key] as? Map<String, Any?> ?: emptyMap()
fun Map<String, Any?>.longAt(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L
fun Map<String, Any?>.doubleAt(key: String): Double = (this[key] as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: 0.0
