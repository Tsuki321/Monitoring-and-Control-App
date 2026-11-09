package com.watermonitor.app.data.history

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.google.firebase.database.*
import com.watermonitor.app.data.repository.FilterHealthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

/** Spark coordinator: a renewable database lease selects a phone; the monthly forecast is immutable. */
object MonthlyReporter {
    private val firestore get() = FirebaseFirestore.getInstance()
    data class ArchiveRow(val id: String, val time: Long, val kind: String, val payload: String)

    suspend fun readArchive(month: String): List<HistoryRecord> {
        val query = firestore.collection("systems/${HistoryRepository.SYSTEM_ID}/months/$month/uploads")
            .orderBy(FieldPath.documentId()).limit(100)
        val unique = mutableMapOf<String, HistoryRecord>()
        var last: DocumentSnapshot? = null
        do {
            val page = (last?.let { query.startAfter(it) } ?: query).get(Source.SERVER).await()
            withContext(Dispatchers.Default) {
                for (doc in page.documents) {
                    val text = doc.getString("rows") ?: error("Archive batch has no records")
                    val rows = HistoryRepository.gson.fromJson(text, Array<ArchiveRow>::class.java)
                    check(rows.size == doc.getLong("count")?.toInt()) { "Invalid archive batch count" }
                    for (row in rows) {
                        check(row.time in ArchiveCalendar.start(month) until ArchiveCalendar.end(month)) { "Archive date mismatch" }
                        check(row.kind in listOf("sample", "maintenance", "command", "alert", "pump", "control")) { "Invalid record kind" }
                        unique.putIfAbsent(row.id, HistoryRecord(row.id, month, row.time, row.kind, row.payload, true))
                    }
                }
            }
            last = page.documents.lastOrNull().takeIf { page.size() == 100 }
        } while (last != null)
        return unique.values.toList()
    }

    suspend fun run() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val currentMonth = ArchiveCalendar.month(SharedRecorder.now())
        val monthsRef = firestore.collection("systems/${HistoryRepository.SYSTEM_ID}/months")
        for (monthDoc in monthsRef.get(Source.SERVER).await().documents.sortedBy { it.id }) {
            val month = monthDoc.id
            if (month >= currentMonth) continue
            val reportRef = monthDoc.reference.collection("reports").document("official")
            val summaryRef = monthDoc.reference.collection("analytics").document("summary")
            val revision = monthDoc.getLong("uploadRevision") ?: 0L
            val existing = reportRef.get(Source.SERVER).await()
            if (existing.exists() && summaryRef.get(Source.SERVER).await().getLong("uploadRevision") == revision) continue
            val now = SharedRecorder.now()
            val token = UUID.randomUUID().toString()
            val jobRef = monthDoc.reference.collection("jobs").document("report")
            val acquired = firestore.runTransaction { tx ->
                val job = tx.get(jobRef)
                if ((job.getLong("expiresAt") ?: 0) > now) false else {
                    tx.set(jobRef, mapOf("owner" to user.uid, "token" to token, "expiresAt" to now + 15 * 60_000,
                        "updatedAt" to FieldValue.serverTimestamp())); true
                }
            }.await()
            if (!acquired) continue
            try {
                HistoryRepository.status.value = "Preparing $month analytics on this phone…"
                val rows = readArchive(month)
                val observations = firestore.collection("systems/${HistoryRepository.SYSTEM_ID}/observations")
                    .get(Source.SERVER).await().documents.mapNotNull { it.data }
                val result = withContext(Dispatchers.Default) { MonthlyAnalytics.calculate(rows, month, observations) }
                // The next day's processing window gives other phones time to submit their records.
                val publish = SharedRecorder.now() >= ArchiveCalendar.end(month) + 86_400_000
                firestore.runTransaction { tx ->
                    val lock = tx.get(jobRef)
                    val official = tx.get(reportRef)
                    val latestMonth = tx.get(monthDoc.reference)
                    check(lock.getString("token") == token && (lock.getLong("expiresAt") ?: 0L) > SharedRecorder.now()) {
                        "Processing lease expired; another phone can retry"
                    }
                    check(latestMonth.getLong("uploadRevision") == revision) { "More records arrived; recalculating on retry" }
                    tx.set(summaryRef, result + mapOf("uploadRevision" to revision, "jobToken" to token,
                        "updatedAt" to FieldValue.serverTimestamp()))
                    if (publish && !official.exists()) tx.set(reportRef, result + mapOf("uploadRevision" to revision,
                        "jobToken" to token, "publishedAt" to FieldValue.serverTimestamp()))
                }.await()
            } finally {
                firestore.runTransaction { tx ->
                    val lock = tx.get(jobRef)
                    if (lock.getString("token") == token) tx.update(jobRef, "expiresAt", 0L,
                        "updatedAt", FieldValue.serverTimestamp())
                }.await()
            }
        }
    }

    suspend fun refreshOfficial() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val months = firestore.collection("systems/${HistoryRepository.SYSTEM_ID}/months")
            .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING).get(Source.SERVER).await()
        for (month in months.documents) {
            val report = month.reference.collection("reports").document("official").get(Source.SERVER).await()
            if (!report.exists()) continue
            if (FirebaseAuth.getInstance().currentUser?.uid != user.uid) return
            val payload = report.data ?: continue
            HistoryRepository.official.value = payload
            FilterHealthRepository.refreshOfficial()
            // Use published coefficients for future runtime wear without copying the report to RTDB.
            HistoryRepository.root.child("current").runTransaction(object : Transaction.Handler {
                override fun doTransaction(data: MutableData): Transaction.Result {
                    if (!data.hasChild("sample")) return Transaction.abort()
                    val old = data.child("modelMonth").value as? String
                    if (old != null && old >= month.id) return Transaction.abort()
                    data.child("models").value = payload["models"]
                    data.child("modelMonth").value = month.id
                    return Transaction.success(data)
                }
                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) = Unit
            }, false)
            return
        }
    }
}
