package com.watermonitor.app.notifications

/** One alert per wet episode; an unknown reading must never rearm it. */
internal class LeakAlertPolicy(var leakHandled: Boolean = false) {
    fun onReading(detected: Boolean?, appVisible: Boolean, canNotify: Boolean): Boolean {
        if (detected == false) leakHandled = false
        if (detected != true || leakHandled) return false
        if (appVisible) {
            leakHandled = true
            return false
        }
        if (!canNotify) return false
        leakHandled = true
        return true
    }

    companion object {
        fun parse(value: Any?): Boolean? = when (value) {
            is Boolean -> value
            is Number -> when (value.toDouble()) { 0.0 -> false; 1.0 -> true; else -> null }
            is String -> when (value.lowercase()) { "0", "false" -> false; "1", "true" -> true; else -> null }
            else -> null
        }
    }
}
