package com.watermonitor.app.notifications

import android.content.Context

internal class TankAlertPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("tank_alerts", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    var setupVersion: Int
        get() = prefs.getInt("setup_version", -1)
        set(value) { prefs.edit().putInt("setup_version", value).apply() }

    // Reboot/update must only resume monitoring that was previously started from the UI.
    var startedForUser: String?
        get() = prefs.getString("started_for_user", null)
        set(value) { prefs.edit().putString("started_for_user", value).apply() }

    fun fullHandled(uid: String): Boolean = prefs.getBoolean("full_handled_$uid", false)

    fun leakHandled(uid: String): Boolean = prefs.getBoolean("leak_handled_$uid", false)

    fun setLeakHandled(uid: String, handled: Boolean) {
        if (leakHandled(uid) != handled) {
            prefs.edit().putBoolean("leak_handled_$uid", handled).apply()
        }
    }

    fun setFullHandled(uid: String, handled: Boolean) {
        if (fullHandled(uid) != handled) {
            prefs.edit().putBoolean("full_handled_$uid", handled).apply()
        }
    }
}
