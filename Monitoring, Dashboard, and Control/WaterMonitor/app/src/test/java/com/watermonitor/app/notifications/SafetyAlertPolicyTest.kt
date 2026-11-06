package com.watermonitor.app.notifications

import org.junit.Assert.*
import org.junit.Test

class SafetyAlertPolicyTest {
    private val full = TankReading.parse(100, 3)
    private val low = TankReading.parse(70, 0)

    @Test fun fullTankAlertsOnceInBackgroundAndRearmsAfterDraining() {
        val policy = TankFullAlertPolicy()
        assertFalse(policy.onReading(low, false, true))
        assertTrue(policy.onReading(full, false, true))
        repeat(5) { assertFalse(policy.onReading(full, false, true)) }
        assertFalse(policy.onReading(TankReading.parse(99, 2), false, true))
        assertFalse(policy.onReading(full, false, true))
        assertFalse(policy.onReading(TankReading.parse(95, 2), false, true))
        assertTrue(policy.onReading(full, false, true))
    }

    @Test fun fullTankSeenInAppDoesNotProduceDelayedNotificationOnExit() {
        val policy = TankFullAlertPolicy()
        assertFalse(policy.onReading(full, true, true))
        assertFalse(policy.onReading(full, false, true))
        policy.onReading(low, false, true)
        assertTrue(policy.onReading(full, false, true))
    }

    @Test fun firstBackgroundFullReadingCanAlertWithoutPreviousSample() {
        assertTrue(TankFullAlertPolicy().onReading(full, false, true))
    }

    @Test fun missingOrInvalidTankReadingsCannotAlertOrRearm() {
        val missing = TankReading.parse(null, null)
        val invalid = TankReading.parse(Double.NaN, 3.5)
        assertNull(missing.isFull)
        assertNull(invalid.isFull)
        assertFalse(TankFullAlertPolicy().onReading(invalid, false, true))
        val policy = TankFullAlertPolicy(true)
        policy.onReading(missing, false, true)
        policy.onReading(invalid, false, true)
        assertFalse(policy.onReading(full, false, true))
        assertNull(TankReading.parse(-1, 4).isFull)
        assertNull(TankReading.parse(Double.POSITIVE_INFINITY, "invalid").isFull)
        assertNull(TankReading.parse(101, -1).isFull)
    }

    @Test fun warningOrLevelAloneCanTriggerAndStringNumbersAreAccepted() {
        assertTrue(TankFullAlertPolicy().onReading(TankReading.parse(null, "3"), false, true))
        assertTrue(TankFullAlertPolicy().onReading(TankReading.parse("100", null), false, true))
        assertFalse(TankFullAlertPolicy().onReading(TankReading.parse(90, 2), false, true))
        val policy = TankFullAlertPolicy(true)
        policy.onReading(TankReading.parse(null, 0), false, true)
        assertTrue(policy.onReading(TankReading.parse(null, 3), false, true))
    }

    @Test fun blockedNotificationDoesNotConsumeBackgroundEvent() {
        val tank = TankFullAlertPolicy()
        val leak = LeakAlertPolicy()
        assertFalse(tank.onReading(full, false, false))
        assertFalse(leak.onReading(true, false, false))
        assertTrue(tank.onReading(full, false, true))
        assertTrue(leak.onReading(true, false, true))
    }

    @Test fun leakAlertsOnceAndRearmsOnlyAfterConfirmedDryReading() {
        val policy = LeakAlertPolicy()
        assertTrue(policy.onReading(true, false, true))
        repeat(5) { assertFalse(policy.onReading(true, false, true)) }
        assertFalse(policy.onReading(null, false, true))
        assertFalse(policy.onReading(true, false, true))
        assertFalse(policy.onReading(false, false, true))
        assertTrue(policy.onReading(true, false, true))
    }

    @Test fun leakSeenInAppDoesNotAlertAfterClosing() {
        val policy = LeakAlertPolicy()
        assertFalse(policy.onReading(true, true, true))
        assertFalse(policy.onReading(true, false, true))
        policy.onReading(false, false, true)
        assertTrue(policy.onReading(true, false, true))
    }

    @Test fun restoringHandledEventsPreventsRepeatAlertsAfterProcessRestart() {
        assertFalse(TankFullAlertPolicy(true).onReading(full, false, true))
        assertFalse(LeakAlertPolicy(true).onReading(true, false, true))
    }

    @Test fun tankAndLeakEventsAreIndependent() {
        val tank = TankFullAlertPolicy()
        val leak = LeakAlertPolicy()
        assertTrue(tank.onReading(full, false, true))
        assertTrue(leak.onReading(true, false, true))
        leak.onReading(false, false, true)
        assertFalse(tank.onReading(full, false, true))
        assertTrue(leak.onReading(true, false, true))
    }

    @Test fun leakParserAcceptsFirmwareTypesAndRejectsUnknownValues() {
        listOf(true, 1L, 1.0, "1", "TRUE").forEach { assertEquals(true, LeakAlertPolicy.parse(it)) }
        listOf(false, 0L, 0.0, "0", "false").forEach { assertEquals(false, LeakAlertPolicy.parse(it)) }
        listOf(null, 2, -1, Double.NaN, 1.5, "wet", "").forEach { assertNull(LeakAlertPolicy.parse(it)) }
    }
}
