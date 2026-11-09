package com.watermonitor.app.data.history

import com.watermonitor.app.data.ml.FilterLifeModel
import com.watermonitor.app.data.model.FilterSpecs
import com.watermonitor.app.data.model.ServiceAction
import kotlin.math.max

/** Pure shared-state reducer. RTDB transactions serialize phones without Cloud Functions. */
object SharedFilterEngine {
    val baseline: Map<String, Any?> by lazy {
        FilterSpecs.stages.associate { spec ->
            val fitted = FilterLifeModel.train(spec)
            spec.key to mapOf("coefficients" to fitted.coefficients.toList(), "syntheticSamples" to 400,
                "syntheticFitR2" to fitted.diagnostics.rSquared, "realObservations" to 0, "fittedOnRealData" to false)
        }
    }
    fun fresh(time: Long, revision: Long = 0): Map<String, Any?> = mapOf(
        "installedAt" to time, "revision" to revision, "usage" to 0.0, "hours" to 0.0,
        "measuredHours" to 0.0, "sums" to listOf(0.0, 0.0, 0.0), "rinses" to 0,
        "intervalComplete" to true, "serviceAt" to time)

    fun advance(old: Map<String, Any?>, sample: Map<String, Any?>): Map<String, Any?> {
        val previous = old.mapAt("sample")
        if (sample.longAt("time") <= previous.longAt("time")) return old
        val time = sample.longAt("time")
        val sensors = sample.mapAt("sensors")
        val before = previous.mapAt("sensors")["runtimeB"] as? Number
        val after = sensors["runtimeB"] as? Number
        val elapsed = (time - previous.longAt("time")) / 3_600_000.0
        val counterValid = before != null && after != null && before.toLong() >= 0 && after.toLong() >= before.toLong()
        val candidate = if (counterValid) (after!!.toLong() - before!!.toLong()) / 3600.0 else 0.0
        val plausible = previous.isNotEmpty() && candidate <= elapsed + 5.0 / 3600
        val hours = if (plausible) candidate else 0.0
        val measured = counterValid && plausible && elapsed > 0 && elapsed <= 3.0 / 60 && valid(sensors)
        val features = FilterLifeModel.features(sensors.doubleAt("tds"), sensors.doubleAt("turbidity"), sensors.doubleAt("ph"))
        val models = old.mapAt("models").ifEmpty { baseline }
        val stages = FilterSpecs.stages.associate { spec ->
            val stage = old.mapAt("stages").mapAt(spec.key).ifEmpty { fresh(time) }
            val next = stage.toMutableMap()
            if (previous.isNotEmpty() && stage.longAt("serviceAt") <= previous.longAt("time")) {
                val coefficients = (models.mapAt(spec.key)["coefficients"] as? List<*>)
                    ?.map { (it as Number).toDouble() }?.toDoubleArray() ?: doubleArrayOf(1.0, 0.0, 0.0, 0.0)
                val factor = if (measured) FilterLifeModel.loadFactor(coefficients, features) else 1.0
                next["usage"] = stage.doubleAt("usage") + hours * factor / spec.ratedHours
                next["hours"] = stage.doubleAt("hours") + hours
                if (!measured) next["intervalComplete"] = false
                if (measured) {
                    next["measuredHours"] = stage.doubleAt("measuredHours") + hours
                    val sums = stage["sums"] as? List<*> ?: listOf(0.0, 0.0, 0.0)
                    next["sums"] = (0..2).map { (sums[it] as Number).toDouble() + hours * features[it + 1] }
                }
            } else if (previous.isNotEmpty()) {
                // First post-service interval straddles the reset: count its runtime conservatively,
                // exclude its water-quality features, and let the 95% coverage gate assess the cycle.
                next["hours"] = stage.doubleAt("hours") + hours
                next["usage"] = stage.doubleAt("usage") + hours / spec.ratedHours
                if (!counterValid || !plausible || elapsed > 3.0 / 60) next["intervalComplete"] = false
            }
            spec.key to next
        }
        val result = old.toMutableMap().apply {
            this["sample"] = sample; this["stages"] = stages; this["models"] = models
            this["runtimeHours"] = old.doubleAt("runtimeHours") + hours
            this["wallHours"] = old.doubleAt("wallHours") + if (previous.isEmpty()) 0.0 else max(0.0, elapsed)
        }
        val beforeSensors = previous.mapAt("sensors")
        if (previous.isNotEmpty() && (listOf("rainDetected", "tankWarning").any {
            (beforeSensors[it] as? Number)?.toInt() != (sensors[it] as? Number)?.toInt()
        })) {
            result["alert"] = mapOf("id" to sample["id"], "time" to time,
                "rainDetected" to sensors["rainDetected"], "tankWarning" to sensors["tankWarning"])
        }
        return result
    }

    fun valid(sensors: Map<String, Any?>): Boolean = listOf("ph", "tds", "turbidity").all {
        sensors[it] is Number && (sensors[it] as Number).toDouble().isFinite()
    } && sensors.doubleAt("ph") in 0.0..14.0 && sensors.doubleAt("tds") >= 0 &&
        sensors.doubleAt("turbidity") >= 0 && sensors.doubleAt("turbidity") < 2999

    fun service(old: Map<String, Any?>, key: String, action: String, revision: Long,
                reason: String, condition: String, actor: String, id: String, time: Long): Map<String, Any?>? {
        if (old.mapAt("applied").containsKey(id)) return old
        val stage = old.mapAt("stages").mapAt(key)
        val spec = FilterSpecs.stages.find { it.key == key } ?: return null
        if (stage.isEmpty() || stage.longAt("revision") != revision || action !in listOf("REPLACE", "RINSE")) return null
        if (action == "RINSE" && spec.action != ServiceAction.RINSE) return null
        if (reason.length > 1000 || condition !in listOf("EXHAUSTED", "PREVENTIVE", "DAMAGED", "RINSED")) return null
        val next = if (action == "REPLACE") fresh(time, revision + 1) else stage.toMutableMap().apply {
            this["revision"] = revision + 1; this["usage"] = stage.doubleAt("usage") * .3
            this["rinses"] = stage.longAt("rinses") + 1; this["serviceAt"] = time
        }
        val event = mutableMapOf<String, Any?>("id" to id, "time" to time, "stageKey" to key,
            "action" to action, "actor" to actor, "reason" to reason, "condition" to condition,
            "before" to stage, "after" to next, "revision" to revision + 1)
        val hours = stage.doubleAt("hours"); val measured = stage.doubleAt("measuredHours")
        if (action == "REPLACE" && condition == "EXHAUSTED" && stage["intervalComplete"] == true &&
            stage.longAt("rinses") == 0L && hours > 0 && measured >= .95 * hours) {
            val sums = stage["sums"] as List<*>
            event["observation"] = mapOf("stageKey" to key, "time" to time, "eventId" to id,
                "features" to (listOf(1.0) + sums.map { (it as Number).toDouble() / measured }),
                "target" to (spec.ratedHours / hours).coerceIn(.25, 4.0))
        }
        return old.toMutableMap().apply {
            this["stages"] = old.mapAt("stages").toMutableMap().apply { put(key, next) }
            // A bounded handoff buffer, not a realtime historical database. Every connected phone journals these.
            this["applied"] = old.mapAt("applied").toMutableMap().apply { put(id, event) }
                .entries.sortedByDescending { (_, value) -> ((value as? Map<*, *>)?.get("time") as? Number)
                    ?.toLong() ?: 0L }.take(20).associate { it.toPair() }
        }
    }
}
