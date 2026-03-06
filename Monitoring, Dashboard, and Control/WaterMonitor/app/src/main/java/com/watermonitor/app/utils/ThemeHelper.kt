package com.watermonitor.app.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeHelper {

    private const val KEY_DARK_MODE = "dark_mode"

    const val LIGHT_MODE = AppCompatDelegate.MODE_NIGHT_NO
    const val DARK_MODE = AppCompatDelegate.MODE_NIGHT_YES
    const val FOLLOW_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

    fun applyTheme(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun saveThemeMode(context: Context, mode: Int) {
        context.getSharedPreferences(HYDROSENSE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DARK_MODE, mode)
            .apply()
    }

    fun getSavedThemeMode(context: Context): Int {
        return context.getSharedPreferences(HYDROSENSE_PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_DARK_MODE, FOLLOW_SYSTEM)
    }
}
