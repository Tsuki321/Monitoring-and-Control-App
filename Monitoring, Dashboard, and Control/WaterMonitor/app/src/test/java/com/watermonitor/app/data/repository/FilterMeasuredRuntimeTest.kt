package com.watermonitor.app.data.repository

import android.app.Application
import com.watermonitor.app.data.ml.FilterLifeModel
import com.watermonitor.app.data.model.RuntimeSource
import com.watermonitor.app.data.model.SensorData
import com.watermonitor.app.utils.FilterPersistedState
import com.watermonitor.app.utils.FilterPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FilterMeasuredRuntimeTest {
    private val repository = FilterHealthRepository

    @Before
    fun prepareLoadedRepository() {
        // Bypass asynchronous application startup so every case has a deterministic baseline.
        field("appContext", RuntimeEnvironment.getApplication())
        field("persisted", FilterPrefs.default())
        field("models", FilterLifeModel.trainAll())
        field("stateLoaded", true)
        field("hardwareRuntimeSeen", false)
        field("lastSaveMillis", System.currentTimeMillis())
    }

    @Test
    fun missingMeasuredCounterWaitsWithoutAccruingWear() = runBlocking {
        repository.onSensorSample(sample(null))
        val state = repository.filterHealthFlow.value
        assertTrue(state.isWaitingForData)
        assertEquals(RuntimeSource.UNAVAILABLE, state.runtimeSource)
        assertEquals(0.0, state.totalRuntimeHours, 0.0)
        assertTrue(state.stages.all { it.weightedHours == 0.0 })
    }

    @Test
    fun firstCounterAnchorsInsteadOfBillingLifetimeRuntime() = runBlocking {
        repository.onSensorSample(sample(86400L))
        val state = repository.filterHealthFlow.value
        assertFalse(state.isWaitingForData)
        assertEquals(RuntimeSource.HARDWARE, state.runtimeSource)
        assertEquals(0.0, state.totalRuntimeHours, 0.0)
        assertEquals(86400L, persisted().lastRuntimeSecondsB ?: -1L)
        assertTrue(state.diagnostics.all { it.syntheticSamples > 0 })
    }

    @Test
    fun missingThenReturningCounterPreservesMeasuredGapAndHistory() = runBlocking {
        val baseline = FilterPrefs.default().copy(
            lastRuntimeSecondsB = 1000L,
            lastSampleWallMillis = System.currentTimeMillis() - 7_200_000L,
            totalRuntimeHours = 3.0,
            accumulatedWallHours = 6.0
        )
        field("persisted", baseline)
        repository.onSensorSample(sample(null))
        assertEquals(baseline, persisted())
        assertTrue(repository.filterHealthFlow.value.isWaitingForData)
        repository.onSensorSample(sample(4600L))
        val state = repository.filterHealthFlow.value
        assertFalse(state.isWaitingForData)
        assertEquals(4.0, state.totalRuntimeHours, 1e-9)
        assertTrue(state.stages.all { it.weightedHours > 0.0 })
    }

    @Test
    fun sensorFaultDoesNotMoveCounterOrWearAnchors() = runBlocking {
        val baseline = FilterPrefs.default().copy(
            lastRuntimeSecondsB = 1000L,
            lastSampleWallMillis = System.currentTimeMillis() - 3_600_000L,
            totalRuntimeHours = 3.0
        )
        field("persisted", baseline)
        repository.onSensorSample(sample(4600L).copy(turbidity = 3000.0))
        assertEquals(baseline, persisted())
        assertTrue(repository.filterHealthFlow.value.isWaitingForData)
    }

    private fun sample(runtime: Long?) = SensorData(
        ph = 7.0,
        tds = 150,
        turbidity = 1.5,
        timestamp = System.currentTimeMillis(),
        runtimeBSeconds = runtime
    )

    private fun field(name: String, value: Any?) {
        repository.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
    }

    private fun persisted(): FilterPersistedState = repository.javaClass
        .getDeclaredField("persisted").apply { isAccessible = true }.get(null) as FilterPersistedState
}
