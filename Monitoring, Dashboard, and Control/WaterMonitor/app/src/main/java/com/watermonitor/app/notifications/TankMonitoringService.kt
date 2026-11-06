package com.watermonitor.app.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.auth.FirebaseAuth
import com.watermonitor.app.utils.LegalConsentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TankMonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: TankAlertPreferences
    private lateinit var auth: FirebaseAuth
    private var monitoredUser: String? = null
    private var monitoring = false
    private val visibilityObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            TankNotifications.cancelAlerts(this@TankMonitoringService)
        }
    }
    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        if (monitoredUser != null && firebaseAuth.currentUser?.uid != monitoredUser) {
            // Never carry an old account's listener or pending alert into another session.
            stop(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = TankAlertPreferences(this)
        auth = FirebaseAuth.getInstance()
        TankNotifications.createChannels(this)
        // Satisfy the foreground-service deadline before doing any network work.
        ServiceCompat.startForeground(
            this, TankNotifications.MONITOR_ID, TankNotifications.monitoring(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(visibilityObserver)
        auth.addAuthStateListener(authListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uid = auth.currentUser?.uid
        if (uid == null || !prefs.enabled || prefs.startedForUser != uid ||
            !LegalConsentManager.hasCurrentConsent(this, uid) || !TankNotifications.canMonitor(this)
        ) {
            stop(this)
            return START_NOT_STICKY
        }
        if (!monitoring) {
            monitoredUser = uid
            monitoring = true
            val policy = TankFullAlertPolicy(prefs.fullHandled(uid))
            val leakPolicy = LeakAlertPolicy(prefs.leakHandled(uid))
            scope.launch {
                TankTelemetry.readings().collect { reading ->
                    if (auth.currentUser?.uid != uid || !prefs.enabled) {
                        stop(this@TankMonitoringService)
                        return@collect
                    }
                    // An outage/unknown reading must not clear the persisted full-tank latch.
                    if (reading == null) return@collect
                    if (reading.tank.canRearm) TankNotifications.cancelFull(this@TankMonitoringService)
                    if (reading.leakDetected == false) TankNotifications.cancelLeak(this@TankMonitoringService)
                    val visible = ProcessLifecycleOwner.get().lifecycle.currentState
                        .isAtLeast(Lifecycle.State.STARTED)
                    val shouldNotify = policy.onReading(
                        reading.tank, visible, TankNotifications.canNotify(
                            this@TankMonitoringService, TankNotifications.ALERT_CHANNEL))
                    if (shouldNotify && !TankNotifications.showFull(this@TankMonitoringService)) {
                        policy.fullHandled = false
                    }
                    prefs.setFullHandled(uid, policy.fullHandled)
                    val shouldNotifyLeak = leakPolicy.onReading(
                        reading.leakDetected, visible, TankNotifications.canNotify(
                            this@TankMonitoringService, TankNotifications.LEAK_CHANNEL))
                    if (shouldNotifyLeak && !TankNotifications.showLeak(this@TankMonitoringService)) {
                        leakPolicy.leakHandled = false
                    }
                    prefs.setLeakHandled(uid, leakPolicy.leakHandled)
                }
            }
        }
        // System process reclamation may restart us; swiping the task does not stop us.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        auth.removeAuthStateListener(authListener)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(visibilityObserver)
        scope.cancel()
        TankNotifications.cancelAlerts(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        fun start(context: Context, fromVisibleActivity: Boolean = false): Boolean {
            val prefs = TankAlertPreferences(context)
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
            if (!prefs.enabled || !LegalConsentManager.hasCurrentConsent(context, uid)) return false
            TankNotifications.createChannels(context)
            if (!TankNotifications.canMonitor(context)) return false
            if (!fromVisibleActivity && prefs.startedForUser != uid) return false
            val previousUser = prefs.startedForUser
            prefs.startedForUser = uid
            return try {
                ContextCompat.startForegroundService(context, Intent(context, TankMonitoringService::class.java))
                true
            } catch (e: IllegalStateException) {
                // Android may reject a background start after the activity has already closed.
                prefs.startedForUser = previousUser
                Log.w("HydroSenseTank", "Monitoring start deferred until the next app launch", e)
                false
            } catch (e: SecurityException) {
                prefs.startedForUser = previousUser
                Log.w("HydroSenseTank", "Monitoring permission unavailable", e)
                false
            }
        }

        fun stop(context: Context) {
            TankAlertPreferences(context).startedForUser = null
            context.stopService(Intent(context, TankMonitoringService::class.java))
            TankNotifications.cancelAlerts(context)
        }
    }
}
