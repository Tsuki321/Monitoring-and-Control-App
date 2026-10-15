package com.watermonitor.app.utils

import android.content.Context

/**
 * Stores acceptance of the in-app legal documents for each Firebase user.
 *
 * Consent is versioned so that publishing revised Terms or Privacy Policy text
 * can require a fresh acknowledgement without transferring one user's consent
 * to another saved account on the same device.
 */
object LegalConsentManager {

    const val CURRENT_DOCUMENT_VERSION = "2026-10-15"

    private const val PREFS_NAME = "hydrosense_legal"
    private const val CONSENT_KEY_PREFIX = "consent_"

    fun hasCurrentConsent(context: Context, uid: String): Boolean {
        val stored = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(consentKey(uid), null)

        return stored?.startsWith("$CURRENT_DOCUMENT_VERSION|") == true
    }

    fun recordConsent(context: Context, uid: String) {
        val value = "$CURRENT_DOCUMENT_VERSION|${System.currentTimeMillis()}"
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(consentKey(uid), value)
            .apply()
    }

    private fun consentKey(uid: String): String = "$CONSENT_KEY_PREFIX$uid"
}
