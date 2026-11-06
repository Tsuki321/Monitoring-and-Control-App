package com.watermonitor.app.notifications

import android.app.Application
import android.graphics.RectF
import android.view.View
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.descendants
import com.google.android.material.card.MaterialCardView
import com.watermonitor.app.R
import com.watermonitor.app.ui.tutorial.TutorialOverlayView
import com.watermonitor.app.ui.tutorial.TutorialStep
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TutorialPlacementTest {
    private lateinit var controller: ActivityController<AppCompatActivity>
    private lateinit var overlay: TutorialOverlayView

    @Before fun setup() {
        controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_WaterMonitor)
        controller.setup()
        overlay = TutorialOverlayView(controller.get())
        controller.get().setContentView(overlay)
    }

    @After fun cleanup() {
        overlay.release()
        controller.pause().stop().destroy()
    }

    @Test fun balloonStaysBetweenBarsOnShortLandscapeScreenAndContentCanScroll() {
        layout(640, 360)
        overlay.setUsableBand(60, 70)
        overlay.showStep(TutorialStep.DEFAULT.first { it.id == "control_pumps" },
            RectF(20f, 80f, 620f, 210f), 12, 15, false)
        layout(640, 360)
        val balloon = overlay.descendants.filterIsInstance<MaterialCardView>().single()
        assertTrue(balloon.top >= 60)
        assertTrue(balloon.bottom <= 290)
        val scroll = overlay.descendants.filterIsInstance<ScrollView>().single()
        assertTrue(scroll.getChildAt(0).height >= scroll.height)
        assertTrue(balloon.height > 0)
    }

    @Test fun centeredWelcomeStaysWithinAsymmetricUsableBand() {
        layout(360, 800)
        overlay.setUsableBand(150, 50)
        overlay.showStep(TutorialStep.DEFAULT.first(), null, 0, 15, false)
        layout(360, 800)
        val balloon = overlay.descendants.filterIsInstance<MaterialCardView>().single()
        assertTrue(balloon.top >= 150)
        assertTrue(balloon.bottom <= 750)
        assertEquals(450f, (balloon.top + balloon.bottom) / 2f, 2f)
    }

    @Test fun longerStepCanGrowAfterAShortStep() {
        layout(360, 800)
        overlay.setUsableBand(70, 70)
        val pumps = TutorialStep.DEFAULT.first { it.id == "control_pumps" }
        overlay.showStep(pumps.copy(bodyRes = R.string.tutorial_finish_title), null, 0, 2, false)
        layout(360, 800)
        val balloon = overlay.descendants.filterIsInstance<MaterialCardView>().single()
        val shortHeight = balloon.height
        overlay.showStep(pumps, null, 1, 2, true)
        layout(360, 800)
        assertTrue("short=$shortHeight, longer=${balloon.height}", balloon.height > shortHeight)
        assertTrue(balloon.bottom <= 730)
    }

    private fun layout(width: Int, height: Int) {
        overlay.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        overlay.layout(0, 0, width, height)
    }
}
