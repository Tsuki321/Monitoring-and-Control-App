package com.watermonitor.app.utils

import android.content.Context
import com.watermonitor.app.R
import java.security.MessageDigest

/**
 * Stores acceptance of the in-app legal documents for each Firebase user.
 *
 * Acceptance belongs to a user and the exact bundled documents they saw.
 * Editing either document invalidates prior acceptance even if the human-readable
 * revision was not bumped. Legacy date-only records require acknowledgement again.
 */
object LegalConsentManager {

    const val CURRENT_DOCUMENT_VERSION = "2026-10-15.2"

    private const val PREFS_NAME = "hydrosense_legal"
    private const val CONSENT_KEY_PREFIX = "consent_"

    fun hasCurrentConsent(context: Context, uid: String): Boolean {
        val stored = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(consentKey(uid), null) ?: return false

        val parts = stored.split('|')
        return parts.size == 3 &&
            parts[0] == CURRENT_DOCUMENT_VERSION &&
            parts[1] == documentFingerprint(context) &&
            (parts[2].toLongOrNull() ?: 0L) > 0L
    }

    fun recordConsent(context: Context, uid: String) {
        val value = "$CURRENT_DOCUMENT_VERSION|${documentFingerprint(context)}|${System.currentTimeMillis()}"
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(consentKey(uid), value)
            .apply()
    }

    private fun consentKey(uid: String): String = "$CONSENT_KEY_PREFIX$uid"

    private fun documentFingerprint(context: Context): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(context.getString(R.string.legal_terms_body).toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(context.getString(R.string.legal_privacy_body).toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
