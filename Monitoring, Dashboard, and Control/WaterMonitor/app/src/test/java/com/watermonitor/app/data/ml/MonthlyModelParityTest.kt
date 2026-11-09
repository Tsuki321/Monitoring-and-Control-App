package com.watermonitor.app.data.ml

import com.google.gson.Gson
import com.watermonitor.app.data.model.FilterSpecs
import org.junit.Assert.*
import org.junit.Test

class MonthlyModelParityTest {
    @Test fun serverBaselineMatchesTheKotlinGeneratorAndRidgeSolver() {
        val stream = javaClass.classLoader!!.getResourceAsStream("monthly-prior-fixture.json")
        // The revision workflow generates this fixture using the actual Node backend.
        org.junit.Assume.assumeNotNull(stream)
        @Suppress("UNCHECKED_CAST")
        val fixture = stream!!.bufferedReader().use { Gson().fromJson(it, Map::class.java) } as Map<String, Map<String, Any>>
        for (spec in FilterSpecs.stages) {
            val expected = (fixture.getValue(spec.key).getValue("coefficients") as List<*>).map { (it as Number).toDouble() }
            val actual = FilterLifeModel.train(spec).coefficients
            expected.forEachIndexed { i, value -> assertEquals("${spec.key} coefficient $i", value, actual[i], 1e-8) }
        }
    }
}
