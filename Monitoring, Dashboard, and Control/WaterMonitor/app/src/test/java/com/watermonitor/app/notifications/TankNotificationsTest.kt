package com.watermonitor.app.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TankNotificationsTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tank_alerts", Context.MODE_PRIVATE).edit().clear().commit()
        manager = context.getSystemService(NotificationManager::class.java)
        TankNotifications.createChannels(context)
    }

    @Test fun bothAlertChannelsUseHighImportanceAndVibration() {
        for (id in listOf(TankNotifications.ALERT_CHANNEL, TankNotifications.LEAK_CHANNEL)) {
            val channel = manager.getNotificationChannel(id)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
            assertTrue(channel.shouldVibrate())
            assertArrayEquals(longArrayOf(0, 500, 200, 500, 200, 800), channel.vibrationPattern)
        }
    }

    @Test fun monitorNotificationIsQuietAndOngoing() {
        val notification = TankNotifications.monitoring(context)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(notification.channelId).importance)
        assertNotNull(notification.contentIntent)
        assertNull(notification.sound)
        assertNull(notification.vibrate)
    }

    @Test fun simultaneousAlertsDoNotReplaceOneAnotherAndClearOnAppReturn() {
        assertTrue(TankNotifications.showFull(context))
        assertTrue(TankNotifications.showLeak(context))
        assertEquals(setOf(TankNotifications.FULL_ID, TankNotifications.LEAK_ID),
            manager.activeNotifications.map { it.id }.toSet())
        for (entry in manager.activeNotifications) {
            assertNotNull(entry.notification.contentIntent)
            assertTrue(entry.notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        }
        TankNotifications.cancelAlerts(context)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun handledEventsPersistIndependentlyForEachAccount() {
        val prefs = TankAlertPreferences(context)
        prefs.setFullHandled("alice", true)
        prefs.setLeakHandled("alice", true)
        val restored = TankAlertPreferences(context)
        assertTrue(restored.fullHandled("alice"))
        assertTrue(restored.leakHandled("alice"))
        assertFalse(restored.fullHandled("bob"))
        assertFalse(restored.leakHandled("bob"))
        restored.setLeakHandled("alice", false)
        assertTrue(restored.fullHandled("alice"))
        assertFalse(restored.leakHandled("alice"))
    }

    @Test fun alertsDefaultOnAndPermissionSetupIsDueForExistingInstallations() {
        val prefs = TankAlertPreferences(context)
        assertTrue(prefs.enabled)
        assertEquals(-1, prefs.setupVersion)
        prefs.setupVersion = 42
        val restored = TankAlertPreferences(context)
        assertEquals(42, restored.setupVersion)
        assertTrue(restored.enabled)
        restored.enabled = false
        assertFalse(TankAlertPreferences(context).enabled)
    }
}
