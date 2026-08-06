package com.watermonitor.app

import android.app.Application
import com.watermonitor.app.data.repository.FilterHealthRepository

/**
 * Process-level initialisation.
 *
 * [FilterHealthRepository] is started here rather than from `MainActivity.onCreate` because
 * an Activity's `onCreate` re-runs on every `recreate()` — which `SettingsFragment` triggers
 * on theme and locale changes. Each re-run would attach another collector, and every one of
 * them would accrue the same runtime delta, multiplying filter wear by the number of times
 * the user had visited Settings. An `Application` runs once per process and removes the
 * question entirely.
 */
class HydroSenseApp : Application() {

    override fun onCreate() {
        super.onCreate()
        FilterHealthRepository.initialize(this)
    }
}
