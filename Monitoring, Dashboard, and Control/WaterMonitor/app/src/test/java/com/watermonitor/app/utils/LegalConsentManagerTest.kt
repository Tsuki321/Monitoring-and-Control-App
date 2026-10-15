package com.watermonitor.app.utils

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import com.watermonitor.app.R
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LegalConsentManagerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("hydrosense_legal", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun acceptanceAppliesOnlyToTheUserWhoGaveIt() {
        assertFalse(LegalConsentManager.hasCurrentConsent(context, "alice"))

        LegalConsentManager.recordConsent(context, "alice")

        assertTrue(LegalConsentManager.hasCurrentConsent(context, "alice"))
        assertFalse(LegalConsentManager.hasCurrentConsent(context, "bob"))
    }

    @Test
    fun legacyDateOnlyAcceptanceRequiresReviewOfTheRevisedDocuments() {
        context.getSharedPreferences("hydrosense_legal", Context.MODE_PRIVATE).edit()
            .putString("consent_alice", "2026-10-15|1792054800000")
            .commit()

        assertFalse(LegalConsentManager.hasCurrentConsent(context, "alice"))

        LegalConsentManager.recordConsent(context, "alice")
        assertTrue(LegalConsentManager.hasCurrentConsent(context, "alice"))
    }

    @Test
    fun editingTermsRequiresAcceptanceEvenWithoutChangingTheRevision() {
        verifyDocumentChangeRequiresAcceptance(R.string.legal_terms_body)
    }

    @Test
    fun editingPrivacyPolicyRequiresAcceptanceEvenWithoutChangingTheRevision() {
        verifyDocumentChangeRequiresAcceptance(R.string.legal_privacy_body)
    }

    @Test
    fun switchingUiLanguageKeepsAcceptanceWhenLegalDocumentsAreUnchanged() {
        val english = localizedContext(Locale.ENGLISH)
        LegalConsentManager.recordConsent(english, "alice")

        // Legal text currently falls back to the same English documents in Filipino.
        assertTrue(LegalConsentManager.hasCurrentConsent(localizedContext(Locale("fil")), "alice"))
    }

    @Test
    fun aMalformedAcceptanceRecordRequiresReview() {
        LegalConsentManager.recordConsent(context, "alice")
        val preferences = context.getSharedPreferences("hydrosense_legal", Context.MODE_PRIVATE)
        val saved = preferences.getString("consent_alice", null)!!
        preferences.edit().putString("consent_alice", saved.substringBeforeLast('|') + "|invalid").commit()

        assertFalse(LegalConsentManager.hasCurrentConsent(context, "alice"))
    }

    private fun verifyDocumentChangeRequiresAcceptance(documentId: Int) {
        LegalConsentManager.recordConsent(context, "alice")
        val revised = withRevisedDocument(documentId)

        assertFalse(LegalConsentManager.hasCurrentConsent(revised, "alice"))

        LegalConsentManager.recordConsent(revised, "alice")
        assertTrue(LegalConsentManager.hasCurrentConsent(revised, "alice"))
        assertFalse(LegalConsentManager.hasCurrentConsent(context, "alice"))
        assertFalse(LegalConsentManager.hasCurrentConsent(revised, "bob"))
    }

    private fun localizedContext(locale: Locale): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(locale)
        })

    @Suppress("DEPRECATION")
    private fun withRevisedDocument(documentId: Int): Context {
        val original = context.resources
        val revised = object : Resources(original.assets, original.displayMetrics, original.configuration) {
            override fun getString(id: Int): String =
                original.getString(id) + if (id == documentId) "\nAn additional disclosed condition." else ""
        }
        return object : ContextWrapper(context) {
            override fun getResources(): Resources = revised
        }
    }
}
