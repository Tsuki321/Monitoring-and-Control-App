package com.watermonitor.app.data.history

import com.watermonitor.app.data.model.FilterSpecs
import org.junit.Assert.*
import org.junit.Test

class SharedHistoryTest {
    private val start = ArchiveCalendar.start("2020-01")
    private fun sample(time: Long, runtime: Long = 0, turbidity: Double = 50.0) = mapOf<String, Any?>(
        "id" to "sample-$time", "time" to time, "sensors" to mapOf("runtimeA" to runtime, "runtimeB" to runtime,
            "ph" to 7.0, "tds" to 300, "turbidity" to turbidity, "rainDetected" to 0, "tankWarning" to 0))

    @Test fun duplicateAndOlderSnapshotsNeverAccrueWearTwice() {
        val initial = SharedFilterEngine.advance(emptyMap(), sample(start))
        val advanced = SharedFilterEngine.advance(initial, sample(start + 60_000, 30))
        assertTrue(advanced.mapAt("stages").mapAt("carbon").doubleAt("usage") > 0)
        assertEquals(advanced, SharedFilterEngine.advance(advanced, sample(start + 60_000, 30)))
        assertEquals(advanced, SharedFilterEngine.advance(advanced, sample(start, 0)))
    }
    @Test fun maintenanceRejectsAStaleRevisionAndRinsingRetainsCalendarAge() {
        var state = SharedFilterEngine.advance(emptyMap(), sample(start))
        state = SharedFilterEngine.advance(state, sample(start + 60_000, 30))
        val rinsed = SharedFilterEngine.service(state, "pumice", "RINSE", 0, "wash", "RINSED", "a", "event", start + 70_000)!!
        assertEquals(start, rinsed.mapAt("stages").mapAt("pumice").longAt("installedAt"))
        assertEquals(1L, rinsed.mapAt("stages").mapAt("pumice").longAt("revision"))
        assertNull(SharedFilterEngine.service(rinsed, "pumice", "RINSE", 0, "wash", "RINSED", "b", "duplicate", start + 80_000))
        assertNull(SharedFilterEngine.service(state, "carbon", "RINSE", 0, "wash", "RINSED", "a", "bad", start + 70_000))
    }
    @Test fun missingReadingsAndCounterResetsExcludeTrainingIntervals() {
        val initial = SharedFilterEngine.advance(emptyMap(), sample(start, 100))
        val invalid = SharedFilterEngine.advance(initial, sample(start + 60_000, 130, 3000.0))
        assertEquals(false, invalid.mapAt("stages").mapAt("carbon")["intervalComplete"])
        assertEquals(30.0 / 3600 / 350, invalid.mapAt("stages").mapAt("carbon").doubleAt("usage"), 1e-12)
        val reset = SharedFilterEngine.advance(initial, sample(start + 60_000, 5))
        assertEquals(false, reset.mapAt("stages").mapAt("carbon")["intervalComplete"])
        val service = SharedFilterEngine.service(reset, "carbon", "REPLACE", 0, "failed", "EXHAUSTED", "a", "e", start + 70_000)!!
        assertTrue(service.mapAt("applied").mapAt("e").mapAt("observation").isEmpty())
    }
    @Test fun databaseNumberTypesDoNotGenerateDuplicateAlerts() {
        val first = SharedFilterEngine.advance(emptyMap(), sample(start))
        assertTrue(first.mapAt("alert").isEmpty())
        val longNumbers = sample(start + 60_000).toMutableMap().apply {
            put("sensors", sample(start).mapAt("sensors") + mapOf("rainDetected" to 0L, "tankWarning" to 0L))
        }
        assertTrue(SharedFilterEngine.advance(first, longNumbers).mapAt("alert").isEmpty())
    }
    @Test fun adequateCoverageProducesDatesAndLaterMonthsCannotLeakIntoTraining() {
        val end = ArchiveCalendar.end("2020-01")
        val count = 5000
        val stages = FilterSpecs.stages.associate { it.key to SharedFilterEngine.fresh(start) }
        val rows = (0 until count).map { i ->
            val time = end - (count - i) * 60_000L
            HistoryRecord("sample-$time", "2020-01", time, "sample",
                HistoryRepository.gson.toJson(mapOf("sample" to sample(time, i * 30L), "stages" to stages)))
        }
        val future = (0..10).map { mapOf<String, Any?>("stageKey" to "carbon", "eventId" to "later-$it",
            "time" to end + 1000, "features" to listOf(1.0,.3,.02,0.0), "target" to 2.0) }
        val report = MonthlyAnalytics.calculate(rows + rows.take(20), "2020-01", future)
        assertEquals(count, report.longAt("recordedMinutes").toInt())
        assertEquals(0L, report.mapAt("models").mapAt("carbon").longAt("realObservations"))
        assertNotNull(report.mapAt("stages").mapAt("carbon")["dueDate"])
        val sparse = MonthlyAnalytics.calculate(rows.take(10), "2020-01", emptyList())
        assertNull(sparse.mapAt("stages").mapAt("carbon")["dueDate"])
        val maintenance = HistoryRecord("maintenance-end", "2020-01", end - 1000, "maintenance",
            HistoryRepository.gson.toJson(mapOf("stageKey" to "carbon", "after" to SharedFilterEngine.fresh(end - 1000, 1))))
        val serviced = MonthlyAnalytics.calculate(rows + maintenance, "2020-01", emptyList())
        assertNull(serviced.mapAt("stages").mapAt("carbon")["dueDate"])
    }
    @Test fun overlappingMinuteRecordsChooseTheLatestSnapshotRegardlessOfUploadOrder() {
        fun row(time: Long, turbidity: Double) = HistoryRecord("minute", "2020-01", time, "sample",
            HistoryRepository.gson.toJson(mapOf("sample" to sample(time, turbidity = turbidity))))
        val older = row(start, 10.0)
        val newer = row(start + 1000, 20.0)
        val first = MonthlyAnalytics.calculate(listOf(older, newer), "2020-01", emptyList())
        val second = MonthlyAnalytics.calculate(listOf(newer, older), "2020-01", emptyList())
        assertEquals(first, second)
        assertEquals(20.0, first.mapAt("metrics").mapAt("turbidity").doubleAt("mean"), 0.0)
    }
}
