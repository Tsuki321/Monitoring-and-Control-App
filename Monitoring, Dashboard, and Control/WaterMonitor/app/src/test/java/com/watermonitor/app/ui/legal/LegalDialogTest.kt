package com.watermonitor.app.ui.legal

import android.app.Application
import android.content.DialogInterface
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.descendants
import com.google.android.material.checkbox.MaterialCheckBox
import com.watermonitor.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Exercises the consent gate with real Android widgets; run by Actions' unit-test step. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LegalDialogTest {
    private lateinit var controller: ActivityController<AppCompatActivity>
    private lateinit var activity: AppCompatActivity

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        activity = controller.get()
        activity.setTheme(R.style.Theme_WaterMonitor)
        controller.setup()
    }

    @After
    fun tearDown() {
        ShadowDialog.getLatestDialog()?.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    fun existingUserCannotBypassConsentWithBack() {
        var accepted = false
        var declined = false
        LegalDialog.showExistingUserConsent(activity, { accepted = true }, { declined = true })
        val dialog = latestDialog()

        pressBack(dialog)

        assertTrue("Back must leave the mandatory consent gate visible", dialog.isShowing)
        assertFalse(accepted)
        assertFalse(declined)
    }

    @Test
    fun existingUserMustCheckAcknowledgementBeforeAccepting() {
        var acceptCount = 0
        var declined = false
        LegalDialog.showExistingUserConsent(activity, { acceptCount++ }, { declined = true })
        val dialog = latestDialog()

        acceptAfterChecking(dialog)

        assertEquals(1, acceptCount)
        assertFalse(declined)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun existingUserCanDeclineWithoutCheckingAcknowledgement() {
        var accepted = false
        var declineCount = 0
        LegalDialog.showExistingUserConsent(activity, { accepted = true }, { declineCount++ })
        val dialog = latestDialog()

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()

        assertFalse(accepted)
        assertEquals(1, declineCount)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun registrationCanBeCancelledWithBackWithoutAccepting() {
        var accepted = false
        LegalDialog.showRegistrationConsent(activity) { accepted = true }
        val dialog = latestDialog()

        pressBack(dialog)

        assertFalse(accepted)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun registrationMustCheckAcknowledgementBeforeAccepting() {
        var acceptCount = 0
        LegalDialog.showRegistrationConsent(activity) { acceptCount++ }
        val dialog = latestDialog()

        acceptAfterChecking(dialog)

        assertEquals(1, acceptCount)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun checkboxRemainsVisibleWhenConsentDialogHeightShrinks() {
        LegalDialog.showExistingUserConsent(activity, {}, {})
        val dialog = latestDialog()
        val checkbox = checkbox(dialog)
        val root = checkbox.parent as ViewGroup
        val scroll = root.descendants.filterIsInstance<ScrollView>().single()

        // Resizing the existing dialog also covers orientation changes handled by MainActivity.
        for (heightDp in listOf(600, 240, 600)) {
            measureAndLayout(root, heightDp)
            assertTrue("Checkbox must have a usable height", checkbox.height > 0)
            assertTrue("Checkbox must fit within the dialog content", checkbox.bottom <= root.height)
            assertTrue("Documents must still have a scrollable viewport", scroll.height > 0)
            assertTrue("Documents must not overlap the checkbox", scroll.bottom <= checkbox.top)
            scroll.isSmoothScrollingEnabled = false
            scroll.fullScroll(View.FOCUS_DOWN)
            assertFalse("The end of the legal text must remain reachable", scroll.canScrollVertically(1))
        }
    }

    @Test
    fun standaloneDocumentsFitAndRemainScrollableInSmallWindows() {
        for (showDocument in listOf(LegalDialog::showTerms, LegalDialog::showPrivacyPolicy)) {
            showDocument(activity)
            val dialog = latestDialog()
            val scroll = (dialog.window!!.decorView as ViewGroup)
                .descendants.filterIsInstance<ScrollView>().single()
            val root = scroll.parent as ViewGroup

            measureAndLayout(root, 240)

            assertTrue("Document viewport must fit within the dialog", scroll.bottom <= root.height)
            assertTrue("Document viewport must have a usable height", scroll.height > 0)
            scroll.isSmoothScrollingEnabled = false
            scroll.fullScroll(View.FOCUS_DOWN)
            assertFalse("The end of the legal text must remain reachable", scroll.canScrollVertically(1))
            dialog.dismiss()
        }
    }

    private fun latestDialog(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun checkbox(dialog: AlertDialog): MaterialCheckBox =
        (dialog.window!!.decorView as ViewGroup).descendants
            .filterIsInstance<MaterialCheckBox>().single()

    private fun acceptAfterChecking(dialog: AlertDialog) {
        val accept = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        val checkbox = checkbox(dialog)
        assertFalse(accept.isEnabled)
        checkbox.isChecked = true
        assertTrue(accept.isEnabled)
        checkbox.isChecked = false
        assertFalse(accept.isEnabled)
        checkbox.isChecked = true
        accept.performClick()
    }

    private fun pressBack(dialog: AlertDialog) {
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun measureAndLayout(view: View, heightDp: Int) {
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (heightDp * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}
