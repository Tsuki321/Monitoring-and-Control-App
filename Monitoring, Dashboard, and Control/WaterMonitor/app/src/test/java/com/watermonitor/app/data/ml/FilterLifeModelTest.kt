package com.watermonitor.app.data.ml

import com.watermonitor.app.data.model.FilterSpecs
import com.watermonitor.app.data.model.ServiceAction
import com.watermonitor.app.data.model.WearProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the filter-life feature contract. Pure JVM — no Android, no device — so they
 * run in CI alongside the other unit tests. They pin the shape the rest of the app depends on:
 * a four-wide feature vector, and a fifth stage that is multilayer foam whose noise-free ground
 * truth is exactly neutral at nominal water.
 */
class FilterLifeModelTest {

    /** The fifth stage, by position — [FilterSpecs.stages] is in confirmed physical order. */
    private val foam = FilterSpecs.stages[4]

    @Test
    fun featureCountIsFour() {
        assertEquals(4, FilterLifeModel.FEATURE_COUNT)
    }

    @Test
    fun featureVectorIsFourWide() {
        val features = FilterLifeModel.features(300.0, 50.0, 7.0)
        assertEquals(FilterLifeModel.FEATURE_COUNT, features.size)
    }

    @Test
    fun fifthStageIsMultilayerFoam() {
        assertEquals("multilayer_foam", foam.key)
        assertEquals(WearProfile.MULTILAYER_FOAM, foam.wearProfile)
        assertEquals(ServiceAction.RINSE, foam.action)
    }

    @Test
    fun foamWearIsNeutralAtNominalWater() {
        val rate = SyntheticWearData.trueWearRate(
            foam,
            7.0,
            FilterSpecs.NOMINAL_TDS_PPM,
            FilterSpecs.NOMINAL_TURBIDITY_NTU
        )
        assertEquals(1.0, rate, 1e-9)
    }

    @Test
    fun foamWearIncreasesWithTurbidity() {
        val low = SyntheticWearData.trueWearRate(
            foam,
            7.0,
            FilterSpecs.NOMINAL_TDS_PPM,
            10.0
        )
        val high = SyntheticWearData.trueWearRate(
            foam,
            7.0,
            FilterSpecs.NOMINAL_TDS_PPM,
            500.0
        )
        assertTrue("foam wear should rise with turbidity (low=$low, high=$high)", high > low)
    }

    @Test
    fun syntheticDesignRowsMatchFeatureWidth() {
        val trainingSet = SyntheticWearData.generate(foam, 32)
        assertEquals(32, trainingSet.design.size)
        assertTrue(
            "every row must carry FilterLifeModel.FEATURE_COUNT features",
            trainingSet.design.all { it.size == FilterLifeModel.FEATURE_COUNT }
        )
    }
}
