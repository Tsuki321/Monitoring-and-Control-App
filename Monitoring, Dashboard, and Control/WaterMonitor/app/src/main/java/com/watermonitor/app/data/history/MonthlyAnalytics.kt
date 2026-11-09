package com.watermonitor.app.data.history

import com.watermonitor.app.data.ml.FilterLifeModel
import com.watermonitor.app.data.model.FilterSpecs
import com.watermonitor.app.data.model.WearObservation
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/** The same Kotlin ridge model runs on the phone selected by the monthly processing lease. */
object MonthlyAnalytics {
    const val VERSION = "ridge-monthly-spark-v1"
    @Suppress("UNCHECKED_CAST") fun payload(row: HistoryRecord): Map<String, Any?> =
        HistoryRepository.gson.fromJson(row.payload, Map::class.java) as Map<String, Any?>
    fun observations(records: List<HistoryRecord>): List<Map<String, Any?>> = records.filter { it.kind == "maintenance" }
        .map { payload(it).mapAt("observation") }.filter { it.isNotEmpty() }.distinctBy { it["eventId"] }

    fun calculate(records: List<HistoryRecord>, month: String, observations: List<Map<String, Any?>>): Map<String, Any?> {
        val start = ArchiveCalendar.start(month); val end = ArchiveCalendar.end(month)
        val rows = records.filter { it.time in start until end }.distinctBy { it.id }.sortedWith(compareBy({ it.time }, { it.id }))
        val samples = rows.filter { it.kind == "sample" }.distinctBy { it.time / 60_000 }
        val values = listOf("ph", "tds", "turbidity", "tankLevel", "tankDistanceMm").associateWith { mutableListOf<Double>() }
        val predictionValues = listOf("ph", "tds", "turbidity").associateWith { mutableListOf<Double>() }
        var previous: Pair<Long, Map<String, Any?>>? = null
        var latest: Map<String, Any?> = emptyMap()
        var runtimeA = 0L; var runtimeB = 0L
        for (row in samples) {
            val data = payload(row); val sensors = data.mapAt("sample").mapAt("sensors")
            values.forEach { (key, list) -> (sensors[key] as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let(list::add) }
            if (SharedFilterEngine.valid(sensors)) predictionValues.forEach { (key, list) -> list.add(sensors.doubleAt(key)) }
            previous?.let { (time, before) ->
                fun delta(key: String): Long {
                    val a = (before[key] as? Number)?.toLong(); val b = (sensors[key] as? Number)?.toLong()
                    return if (a != null && b != null && a >= 0 && b >= a && b - a <= (row.time - time) / 1000 + 5) b - a else 0
                }
                runtimeA += delta("runtimeA"); runtimeB += delta("runtimeB")
            }
            previous = row.time to sensors; latest = data
        }
        val coverage = samples.size / ((end - start) / 60000.0)
        val metrics = values.mapValues { (_, v) -> if (v.isEmpty()) null else mapOf("count" to v.size, "min" to v.min(), "max" to v.max(), "mean" to v.average()) }
        val events = rows.filter { it.kind != "sample" }
        val lastTime = samples.lastOrNull()?.time ?: 0
        val span = (lastTime - (samples.firstOrNull()?.time ?: 0)) / 1000.0
        val duty = if (span > 0) (runtimeB / span).coerceIn(0.0, 1.0) else 0.0
        val x = FilterLifeModel.features(predictionValues.getValue("tds").average().takeIf { it.isFinite() } ?: 0.0,
            predictionValues.getValue("turbidity").average().takeIf { it.isFinite() } ?: 0.0,
            predictionValues.getValue("ph").average().takeIf { it.isFinite() } ?: 7.0)
        val trainingInputs = observations.filter { it.longAt("time") < end && it.longAt("time") > 0 }
            .distinctBy { it["eventId"] }.sortedBy { it.longAt("time") }
            .groupBy { it["stageKey"] }.values.flatMap { it.takeLast(FilterSpecs.MAX_OBSERVATIONS_PER_STAGE) }
        val training = trainingInputs.mapNotNull {
            val f = it["features"] as? List<*> ?: return@mapNotNull null
            if (f.size != 4 || f.any { value -> value !is Number || !value.toDouble().isFinite() }) return@mapNotNull null
            if (it.doubleAt("target") !in .25..4.0) return@mapNotNull null
            WearObservation(stageKey = it["stageKey"] as? String ?: return@mapNotNull null,
                tdsNorm = (f[1] as Number).toDouble(), turbidityNorm = (f[2] as Number).toDouble(),
                phDeviation = (f[3] as Number).toDouble(), observedWearRate = it.doubleAt("target"))
        }
        val models = mutableMapOf<String, Any?>(); val stages = mutableMapOf<String, Any?>()
        for (spec in FilterSpecs.stages) {
            val fitted = FilterLifeModel.train(spec, training.filter { it.stageKey == spec.key }
                .takeLast(FilterSpecs.MAX_OBSERVATIONS_PER_STAGE))
            models[spec.key] = mapOf("coefficients" to fitted.coefficients.toList(), "syntheticSamples" to 400,
                "syntheticFitR2" to fitted.diagnostics.rSquared, "realObservations" to fitted.diagnostics.realObservations,
                "fittedOnRealData" to fitted.diagnostics.fittedOnRealData)
            val service = events.asReversed().firstOrNull { it.kind == "maintenance" && it.time > lastTime && payload(it)["stageKey"] == spec.key }
            val stage = service?.let { payload(it).mapAt("after") } ?: latest.mapAt("stages").mapAt(spec.key)
            val validCount = predictionValues.getValue("ph").size
            val sufficient = stage.isNotEmpty() && service == null && validCount >= 60 &&
                validCount / ((end - start) / 60000.0) >= .1 &&
                end - lastTime <= 86_400_000 && duty >= .001 && SharedFilterEngine.valid(latest.mapAt("sample").mapAt("sensors"))
            val remaining = max(0.0, 1 - stage.doubleAt("usage")) * spec.ratedHours / FilterLifeModel.loadFactor(fitted.coefficients, x)
            val calendarDue = stage.longAt("installedAt") + spec.ratedDays * 86_400_000
            val runtimeDue = if (duty > 0) lastTime + remaining / (24 * duty) * 86_400_000 else Double.POSITIVE_INFINITY
            val due = min(calendarDue, runtimeDue)
            stages[spec.key] = mapOf("installedAt" to stage.longAt("installedAt"), "revision" to stage.longAt("revision"),
                "dueDate" to if (sufficient) Instant.ofEpochMilli(due.toLong()).atZone(ArchiveCalendar.zone).toLocalDate().toString() else null,
                "action" to if (calendarDue <= runtimeDue) "REPLACE" else spec.action.name,
                "status" to if (!sufficient) "INSUFFICIENT_DATA" else if (fitted.diagnostics.fittedOnRealData) "FIELD_ADAPTED" else "BASELINE")
        }
        return mapOf("month" to month, "cutoff" to end, "modelVersion" to VERSION, "models" to models,
            "stages" to stages, "metrics" to metrics, "coverage" to coverage, "recordedMinutes" to samples.size,
            "gapMinutes" to max(0.0, (end - start) / 60000.0 - samples.size),
            "runtimeSeconds" to mapOf("A" to runtimeA, "B" to runtimeB),
            "observationIds" to trainingInputs.map { it["eventId"] },
            "observationDigest" to HistoryRepository.hash(HistoryRepository.gson.toJson(trainingInputs)),
            "eventCounts" to listOf("alert", "pump", "control", "command", "maintenance").associateWith { kind -> events.count { it.kind == kind } },
            "inputRecordCount" to rows.size, "inputDigest" to HistoryRepository.hash(rows.joinToString("\n") { it.id + it.payload }))
    }
}
