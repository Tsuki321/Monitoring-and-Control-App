package com.watermonitor.app.data.history

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

class MonthlyUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val user = FirebaseAuth.getInstance().currentUser ?: return Result.retry()
        val dao = HistoryDatabase.get(applicationContext).history()
        val currentMonth = ArchiveCalendar.month(System.currentTimeMillis())
        return try {
            // Limit each run; the periodic task continues a large backlog without timing out.
            repeat(40) {
                val pending = dao.pending(currentMonth)
                if (pending.isEmpty()) {
                    HistoryRepository.status.value = "Completed months uploaded • this month is recorded locally"
                    return Result.success()
                }
                val month = pending.first().month
                val rows = pending.takeWhile { it.month == month }
                val encoded = HistoryRepository.gson.toJson(rows.map { mapOf(
                    "id" to it.id, "time" to it.time, "kind" to it.kind, "payload" to it.payload) })
                val digest = HistoryRepository.hash(encoded)
                val ref = FirebaseFirestore.getInstance().document(
                    "systems/${HistoryRepository.SYSTEM_ID}/months/$month/uploads/${user.uid}-$digest")
                HistoryRepository.status.value = "Uploading $month history…"
                // Immutable content-addressed batch: safe after process death or a lost acknowledgement.
                if (!ref.get(Source.SERVER).await().exists()) {
                    ref.set(mapOf("schemaVersion" to 1, "owner" to user.uid, "month" to month,
                        "monthEnd" to ArchiveCalendar.end(month), "rows" to encoded,
                        "count" to rows.size, "receivedAt" to FieldValue.serverTimestamp())).await()
                }
                dao.acknowledge(rows.map { it.id })
            }
            Result.retry()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            HistoryRepository.status.value = "Monthly upload pending: ${error.localizedMessage}"
            Result.retry()
        }
    }
}
