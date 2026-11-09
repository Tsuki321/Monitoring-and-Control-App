package com.watermonitor.app.data.history

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Atomic, immutable closed-month batches. No paid Cloud Functions or per-reading cloud writes. */
class MonthlyUploadWorker(app: Context, params: WorkerParameters) : CoroutineWorker(app, params) {
    override suspend fun doWork(): Result {
        val user = FirebaseAuth.getInstance().currentUser ?: return Result.success()
        val db = FirebaseFirestore.getInstance()
        val dao = HistoryDatabase.get(applicationContext).history()
        try {
            val monthNow = ArchiveCalendar.month(SharedRecorder.now())
            repeat(100) {
                val pending = dao.pending(monthNow)
                if (pending.isEmpty()) {
                    MonthlyReporter.run()
                    MonthlyReporter.refreshOfficial()
                    HistoryRepository.status.value = "Closed months synchronized • collecting this month locally"
                    return Result.success()
                }
                val month = pending.first().month
                var rows = pending.filter { it.month == month }
                fun encode() = HistoryRepository.gson.toJson(rows.map {
                    MonthlyReporter.ArchiveRow(it.id, it.time, it.kind, it.payload)
                })
                var encoded = encode()
                while (encoded.toByteArray(Charsets.UTF_8).size > 700_000 && rows.size > 1) {
                    rows = rows.take(rows.size / 2); encoded = encode()
                }
                check(encoded.toByteArray(Charsets.UTF_8).size <= 700_000) { "Archive record exceeds upload size limit" }
                val parent = db.document("systems/${HistoryRepository.SYSTEM_ID}/months/$month")
                val ref = parent.collection("uploads").document(HistoryRepository.hash(encoded))
                val observations = MonthlyAnalytics.observations(rows)
                db.runTransaction { tx ->
                    val receipt = tx.get(ref)
                    val monthDoc = tx.get(parent)
                    val observationDocs = observations.associate { observation ->
                        val obsRef = db.document("systems/${HistoryRepository.SYSTEM_ID}/observations/${observation["eventId"]}")
                        obsRef to tx.get(obsRef)
                    }
                    if (!receipt.exists()) {
                        tx.set(ref, mapOf("schemaVersion" to 1, "owner" to user.uid, "month" to month,
                            "monthEnd" to ArchiveCalendar.end(month), "rows" to encoded, "count" to rows.size,
                            "receivedAt" to FieldValue.serverTimestamp()))
                        tx.set(parent, mapOf("month" to month, "monthEnd" to ArchiveCalendar.end(month),
                            "lastBatch" to ref.id,
                            "uploadRevision" to ((monthDoc.getLong("uploadRevision") ?: 0L) + 1),
                            "updatedAt" to FieldValue.serverTimestamp()))
                    } else check(receipt.getString("rows") == encoded) { "Archive receipt does not match local data" }
                    observations.forEach { observation ->
                        val obsRef = db.document("systems/${HistoryRepository.SYSTEM_ID}/observations/${observation["eventId"]}")
                        if (observationDocs.getValue(obsRef).exists().not()) {
                            tx.set(obsRef, observation + mapOf("month" to month, "batch" to ref.id))
                        }
                    }
                }.await()
                // Server read avoids acknowledging queued offline writes as uploaded.
                check(ref.get(Source.SERVER).await().getString("rows") == encoded) { "Archive confirmation unavailable" }
                dao.acknowledge(rows.map { it.id })
            }
            return Result.retry()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            HistoryRepository.status.value = "Monthly sync pending: ${error.localizedMessage}. Local records retained."
            return Result.retry()
        }
    }
}
