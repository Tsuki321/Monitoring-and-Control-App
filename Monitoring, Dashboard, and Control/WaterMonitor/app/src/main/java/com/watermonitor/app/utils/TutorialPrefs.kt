package com.watermonitor.app.utils

import android.content.Context

/**
 * Per-account record of whether the guided tour has been completed.
 *
 * Keyed by Firebase UID rather than stored as a single flag: the tour is offered to each
 * account once, so signing in as a different user on the same device still gets a tour.
 * The flag is set only when the user finishes the tour (or taps "Skip"), and "Replay tour"
 * in Settings clears it.
 */
object TutorialPrefs {

    private const val KEY_PREFIX = "tutorial_done_"

    fun hasCompleted(context: Context, uid: String): Boolean {
        if (uid.isEmpty()) return false
        return prefs(context).getBoolean(KEY_PREFIX + uid, false)
    }

    fun markCompleted(context: Context, uid: String) {
        if (uid.isEmpty()) return
        prefs(context).edit().putBoolean(KEY_PREFIX + uid, true).apply()
    }

    fun reset(context: Context, uid: String) {
        if (uid.isEmpty()) return
        prefs(context).edit().remove(KEY_PREFIX + uid).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(HYDROSENSE_PREFS, Context.MODE_PRIVATE)
}
