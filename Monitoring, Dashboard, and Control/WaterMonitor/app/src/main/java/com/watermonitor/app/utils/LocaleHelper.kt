package com.watermonitor.app.utils

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {

    private const val KEY_LANGUAGE = "language"

    const val LANG_ENGLISH = "en"
    const val LANG_FILIPINO = "fil"

    fun wrap(context: Context): Context {
        val language = getSavedLanguage(context)
        return applyLocale(context, language)
    }

    fun applyLocale(context: Context, language: String): Context {
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    fun saveLanguage(context: Context, language: String) {
        context.getSharedPreferences(HYDROSENSE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language)
            .apply()
    }

    fun getSavedLanguage(context: Context): String {
        return context.getSharedPreferences(HYDROSENSE_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, LANG_ENGLISH) ?: LANG_ENGLISH
    }
}
