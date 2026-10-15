package com.watermonitor.app.ui.legal

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.watermonitor.app.R

/** Displays the bundled legal documents and the registration consent gate. */
object LegalDialog {

    fun showTerms(context: Context) {
        showDocument(
            context = context,
            title = R.string.legal_terms_title,
            body = R.string.legal_terms_body
        )
    }

    fun showPrivacyPolicy(context: Context) {
        showDocument(
            context = context,
            title = R.string.legal_privacy_title,
            body = R.string.legal_privacy_body
        )
    }

    fun showLicense(context: Context) {
        showDocument(
            context = context,
            title = R.string.legal_license_title,
            body = R.string.legal_license_body
        )
    }

    fun showRegistrationConsent(
        context: Context,
        onAccepted: () -> Unit
    ) {
        val content = createConsentContent(context)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.legal_consent_title)
            .setView(content.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.legal_accept, null)
            .create()

        dialog.setOnShowListener {
            val acceptButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            acceptButton.isEnabled = false
            content.checkbox.setOnCheckedChangeListener { _, checked ->
                acceptButton.isEnabled = checked
            }
            acceptButton.setOnClickListener {
                dialog.dismiss()
                onAccepted()
            }
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    fun showExistingUserConsent(
        context: Context,
        onAccepted: () -> Unit,
        onDeclined: () -> Unit
    ) {
        val content = createConsentContent(context)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.legal_existing_user_title)
            .setView(content.root)
            .setCancelable(false)
            .setNegativeButton(R.string.legal_decline_logout, null)
            .setPositiveButton(R.string.legal_accept, null)
            .create()

        dialog.setOnShowListener {
            val acceptButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            acceptButton.isEnabled = false
            content.checkbox.setOnCheckedChangeListener { _, checked ->
                acceptButton.isEnabled = checked
            }
            acceptButton.setOnClickListener {
                dialog.dismiss()
                onAccepted()
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                onDeclined()
            }
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private fun showDocument(context: Context, title: Int, body: Int) {
        val bodyView = TextView(context).apply {
            text = context.getString(body)
            textSize = 14f
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(context).apply {
            addView(bodyView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isFillViewport = true
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), 0, dp(context, 24), 0)
            addView(scrollView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 480), 1f
            ))
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(root)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun createConsentContent(context: Context): ConsentContent {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), 0, dp(context, 24), 0)
        }

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
        }
        val documents = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        documents.addHeading(context, R.string.legal_changes_title)
        documents.addBody(context, R.string.legal_changes_summary)
        documents.addHeading(context, R.string.legal_terms_title)
        documents.addBody(context, R.string.legal_terms_body)
        documents.addHeading(context, R.string.legal_privacy_title)
        documents.addBody(context, R.string.legal_privacy_body)
        scrollView.addView(documents)
        // Let the document viewport shrink when the dialog is height-constrained,
        // keeping the acknowledgement checkbox reachable in landscape/multi-window.
        root.addView(scrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 400), 1f
        ))

        val checkbox = MaterialCheckBox(context).apply {
            text = context.getString(R.string.legal_consent_checkbox)
            isFocusable = true
            setPadding(0, dp(context, 12), 0, 0)
        }
        root.addView(checkbox, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        return ConsentContent(root, checkbox)
    }

    private fun LinearLayout.addHeading(context: Context, textRes: Int) {
        addView(TextView(context).apply {
            text = context.getString(textRes)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(context, 16), 0, dp(context, 8))
        })
    }

    private fun LinearLayout.addBody(context: Context, textRes: Int) {
        addView(TextView(context).apply {
            text = context.getString(textRes)
            textSize = 14f
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
        })
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private data class ConsentContent(
        val root: View,
        val checkbox: MaterialCheckBox
    )
}
