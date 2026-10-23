package com.watermonitor.app.ui.tutorial

import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.annotation.IdRes
import androidx.core.view.children
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import com.google.firebase.auth.FirebaseAuth
import com.watermonitor.app.R
import com.watermonitor.app.databinding.ActivityMainBinding
import com.watermonitor.app.utils.TutorialPrefs

/**
 * Runs the guided tour: navigates to each step's screen, finds its target in the live view
 * tree, and hands a spotlight rect to [TutorialOverlayView].
 *
 * Navigation is real — the tour switches bottom-nav tabs the same way a user does, because
 * the overlay only dims the screen; it never fakes a snapshot of another screen. A step whose
 * target is not laid out (a card below the fold, a switch hidden by the leak banner) is
 * skipped rather than spotlighting the wrong place.
 *
 * Timing: right after a navigation the destination's view tree does not exist yet. Rather
 * than sleeping, [showCurrentStep] re-checks once per frame until the target appears, giving
 * up after [MAX_TARGET_ATTEMPTS] frames.
 */
class TutorialController(
    private val activity: ComponentActivity,
    private val binding: ActivityMainBinding,
    private val navController: NavController,
    private val onFinished: () -> Unit
) {

    private val overlay: TutorialOverlayView =
        TutorialOverlayView(activity).also {
            it.setOnNextClickListener { advance() }
            it.setOnBalloonClickListener { advance() }
            it.setOnSkipClickListener { skip() }
            it.setOnBackPressedListener { skip() }
        }

    private val steps: List<TutorialStep> = TutorialStep.DEFAULT
    private var index = 0
    private var overlayAttached = false
    private var targetAttempts = 0

    /** True while the overlay is up; read by the host when saving instance state. */
    val isShowing: Boolean get() = overlayAttached

    /**
     * Guard for the end of a run. `post`/`postOnAnimation` callbacks and a quick double tap
     * on "Next" can both land after the tour has already finished; without this, advancing
     * past the last step would finish twice and the stale callback could restart the run.
     */
    private var finished = false

    /** True while the tour is itself navigating, so it ignores its own destination event. */
    private var navigatingProgrammatically = false

    /** True while the pending target's ScrollView is still smooth-scrolling. */
    private var scrollAnimating = false

    // ---------------------------------------------------------------- public API

    /** Shows the tour from the beginning. Safe to call again after a previous run. */
    fun start() {
        finished = false
        index = 0
        targetAttempts = 0
        scrollAnimating = false
        navigatingProgrammatically = false
        attachOverlay()
        showCurrentStep()
    }

    /** Ends the tour and records completion so it is not offered again for this account. */
    fun finish() {
        if (finished) return
        finished = true
        detachOverlay()
        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            TutorialPrefs.markCompleted(activity, uid)
        }
        onFinished()
    }

    /**
     * "Skip" also records completion: a user who steps out of a fourteen-page tour should
     * not be walked through it again on every launch. Settings → Replay tour reopens it.
     */
    private fun skip() {
        if (finished || inputLocked) return
        inputLocked = true
        // A finger already travelling toward "Next" must not land on whatever the next
        // screen shows once the overlay is gone. Disabling the whole subtree (not just the
        // overlay) is what blocks the balloon's children for the one frame before removal.
        overlay.isEnabled = false
        overlay.children.forEach { it.isEnabled = false }
        binding.root.post { finish() }
    }

    /**
     * Hides the tour when the app navigates somewhere the current step does not live.
     *
     * The step's own tab switch must not count: it fires this listener, and at that moment
     * the destination is still the previous screen — checking it would tear the tour down
     * on its first navigation.
     */
    fun onDestinationChanged(@IdRes destinationId: Int) {
        if (!overlayAttached) return
        // Our own navigation always lands on the pending step's destination, so a hit there
        // just clears the expectation — the event can arrive during navigate() itself, so
        // the flag is not consumed unconditionally.
        if (navigatingProgrammatically) {
            val pending = steps.getOrNull(index) ?: return
            if (destinationId == idFor(pending.destination)) navigatingProgrammatically = false
            return
        }
        val step = steps.getOrNull(index) ?: return
        if (destinationId != idFor(step.destination)) detachOverlay()
    }

    // ---------------------------------------------------------------- overlay

    private fun attachOverlay() {
        if (overlayAttached) return
        if (overlay.parent != null) return
        binding.root.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // The overlay is added to binding.root, so it covers the top bar and bottom navigation
        // too (the tour spots those as targets). The balloon must still stay clear of them,
        // otherwise a long step on a short screen puts "Next" under the bottom bar.
        overlay.elevation = 24f
        overlay.isEnabled = true
        // A previous "Skip" disabled the balloon's children for its exit frame.
        overlay.children.forEach { it.isEnabled = true }
        overlayAttached = true
        // The tour's own buttons drive it; taps on the dimmed app underneath are swallowed
        // by the overlay's touch handling.
    }

    private fun detachOverlay() {
        if (!overlayAttached) return
        overlay.release()
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        overlayAttached = false
    }

    /**
     * True between an input and the next step actually being shown. While it is set — during
     * a tab switch, a smooth scroll or the wait for a target — a second tap must not advance
     * again, or a normal double tap would silently skip a page (or, at the end, the rest of
     * the tour).
     */
    private var inputLocked = false

    private fun advance() {
        if (finished || inputLocked) return
        inputLocked = true
        index++
        targetAttempts = 0
        if (index >= steps.size) finish() else showCurrentStep()
    }

    /**
     * Drops a step whose target could not be found and moves to the next one that belongs
     * to a reachable destination, so one hidden card does not abort the whole tour.
     */
    private fun skipCurrent() {
        index++
        targetAttempts = 0
        if (index >= steps.size) finish() else showCurrentStep()
    }

    // ---------------------------------------------------------------- stepping

    private fun showCurrentStep() {
        if (finished) return
        val step = steps.getOrNull(index) ?: run {
            finish()
            return
        }
        if (!overlayAttached) attachOverlay()
        val root = activity.window.decorView

        // A freshly attached overlay is not measured yet; its balloon and band are sized
        // against its real bounds, so give it one frame first.
        if (!overlay.isSized) {
            root.postOnAnimation { if (!finished && overlayAttached) showCurrentStep() }
            return
        }
        updateUsableBand()

        val wanted = idFor(step.destination)
        if (navController.currentDestination?.id != wanted) {
            navigatingProgrammatically = true
            if (!navigateTo(wanted)) {
                navigatingProgrammatically = false
                skipCurrent()
                return
            }
            targetAttempts = 0
            // Re-check on the next frame: the destination's view is inflated then, so a
            // same-frame post would always be one iteration behind.
            root.postOnAnimation { if (!finished && overlayAttached) showCurrentStep() }
            return
        }

        // The opening page introduces the tour in the middle of the screen, but only when
        // there is genuinely room for a centred balloon. On a folded or landscape screen the
        // centred card would fight the first highlighted card right below it, so there we go
        // straight to the points.
        if (step.anchor == SpotlightAnchor.NONE && index == 0 &&
            navController.currentDestination?.id == R.id.dashboardFragment
        ) {
            val safetyCard = navView(R.id.cardWaterSafety)
            if (safetyCard != null && safetyCard.isShown && !fitsOnScreen(safetyCard)) {
                skipCurrent()
                return
            }
        }

        val target = findTarget(step)
        if (target == null) {
            retryOrSkip(root)
            return
        }

        val rect = spotlightFor(target, step.anchor)
        if (rect == null && step.anchor != SpotlightAnchor.NONE) {
            // The view exists but is not laid out yet (zero-size column on first layout)
            // or could not be scrolled into view: retry within the same frame budget.
            retryOrSkip(root)
            return
        }
        if (scrollAnimating) {
            // Hold the balloon back until the smooth scroll settles, so it never points at
            // a position the card is leaving.
            root.postOnAnimation { if (!finished && overlayAttached) showCurrentStep() }
            return
        }
        targetAttempts = 0
        inputLocked = false

        overlay.showStep(
            step = step,
            spotlight = rect,
            stepIndex = index,
            stepCount = steps.size,
            isLast = index == steps.lastIndex
        )
    }

    /**
     * Reserves the top bar and bottom navigation inside the overlay. The overlay is attached
     * to the activity root (it has to be able to spot the gear and the tabs), so without
     * this the balloon could sit on top of them.
     */
    private fun updateUsableBand() {
        val barTop = binding.topBar.top - overlay.top
        val barBottom = overlay.bottom - binding.bottomNav.top
        overlay.setUsableBand(
            barTop.coerceIn(0, overlay.height / 3),
            barBottom.coerceIn(0, overlay.height / 3)
        )
    }

    private fun retryOrSkip(root: View) {
        if (++targetAttempts > MAX_TARGET_ATTEMPTS) {
            skipCurrent()
        } else {
            root.postOnAnimation { if (!finished && overlayAttached) showCurrentStep() }
        }
    }

    // ---------------------------------------------------------------- navigation

    private fun navigateTo(@IdRes destinationId: Int): Boolean = try {
        when (destinationId) {
            R.id.dashboardFragment -> navController.navigate(
                R.id.dashboardFragment,
                null,
                NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build()
            )
            R.id.monitoringFragment -> navController.navigate(
                R.id.monitoringFragment,
                null,
                NavOptions.Builder().setPopUpTo(R.id.dashboardFragment, false).build()
            )
            R.id.controlFragment -> navController.navigate(
                R.id.controlFragment,
                null,
                NavOptions.Builder().setPopUpTo(R.id.dashboardFragment, false).build()
            )
            else -> navController.navigate(destinationId)
        }
        true
    } catch (e: IllegalArgumentException) {
        // Navigating to the destination that is already current.
        false
    }

    @IdRes
    private fun idFor(destination: TutorialDestination): Int = when (destination) {
        TutorialDestination.DASHBOARD -> R.id.dashboardFragment
        TutorialDestination.MONITORING -> R.id.monitoringFragment
        TutorialDestination.CONTROL -> R.id.controlFragment
    }

    // ---------------------------------------------------------------- targets

    /** Resolves a tour target to its live view, or null while it is not on screen. */
    private fun findTarget(step: TutorialStep): View? {
        val view: View? = when (step.target) {
            TutorialTarget.TUTORIAL_HEADER -> binding.tvDateTime
            TutorialTarget.TAB_HOME -> tabView(R.id.dashboardFragment)
            TutorialTarget.TAB_MONITORING -> tabView(R.id.monitoringFragment)
            TutorialTarget.TAB_CONTROL -> tabView(R.id.controlFragment)
            TutorialTarget.SETTINGS_BUTTON -> binding.btnSettings
            // Card IDs are resolved under the nav host rather than the whole window, so a
            // still-attached view from a destination being torn down can never be picked up.
            TutorialTarget.DASHBOARD_SAFETY_CARD -> navView(R.id.cardWaterSafety)
            // Control defines an id of the same name for its own footer card, so the
            // Dashboard card is pinned by the id its layout actually uses.
            TutorialTarget.DASHBOARD_SYSTEM_CARD -> navView(R.id.dashboard_cardSystemStatus)
            TutorialTarget.DASHBOARD_SENSOR_CARD -> navView(R.id.cardSensorStatus)
            TutorialTarget.DASHBOARD_FILTER_CARD -> navView(R.id.cardFilterHealth)
            TutorialTarget.MONITORING_TANK_CARD -> navView(R.id.cardTank)
            TutorialTarget.MONITORING_PH_CARD -> navView(R.id.cardPh)
            TutorialTarget.MONITORING_TDS_CARD -> navView(R.id.cardTds)
            TutorialTarget.MONITORING_TURBIDITY_CARD -> navView(R.id.cardTurbidity)
            TutorialTarget.CONTROL_MODE_CARD -> navView(R.id.cardMode)
            TutorialTarget.CONTROL_PUMP_A_CARD -> navView(R.id.cardPumpA)
            TutorialTarget.CONTROL_PUMP_B_CARD -> navView(R.id.cardPumpB)
        }
        return view?.takeIf { v -> v.isShown && v.width > 0 && v.height > 0 }
    }

    /**
     * Looks a card up under the *current* destination only.
     *
     * The nav host can still hold the outgoing fragment's views for a frame during a
     * transition, and Monitoring/Control reuse some Dashboard ids — a whole-host lookup could
     * spotlight a card on the screen the user is leaving.
     */
    private fun navView(@IdRes id: Int): View? {
        val host = binding.navHostFragment
        val destinationRoot = (0 until host.childCount)
            .map { host.getChildAt(it) }
            .firstOrNull { it.id == navController.currentDestination?.id }
            ?: host
        return destinationRoot.findViewById(id)
    }

    /** The BottomNavigationView renders each item without an item ID — match on its label. */
    private fun tabView(@IdRes menuItemId: Int): View? {
        val title = binding.bottomNav.menu.findItem(menuItemId)?.title?.toString() ?: return null
        return binding.bottomNav.children.firstOrNull { itemView ->
            itemView.children.any { child ->
                val text = (child as? android.widget.TextView)?.text?.toString()
                    ?: (child as? ViewGroup)?.let { group ->
                        group.children.filterIsInstance<android.widget.TextView>()
                            .joinToString("") { it.text ?: "" }
                            .takeIf { it.isNotEmpty() }
                    }
                text == title
            }
        }
    }

    // ---------------------------------------------------------------- geometry

    /**
     * Window-space rect for a step.
     *
     * Scrollable screens (Dashboard, Monitoring, Control) are scrolled by the smallest amount
     * that brings the target into view, because a spotlight can only frame something visible;
     * the rect is then derived from the view's on-screen position, so it is correct at any
     * scroll offset and in either orientation.
     */
    private fun spotlightFor(target: View, anchor: SpotlightAnchor): RectF? {
        if (anchor == SpotlightAnchor.NONE) return null
        binding.root.getWindowVisibleDisplayFrame(SCREEN_FRAME)
        scrollIntoView(target, SCREEN_FRAME.top, SCREEN_FRAME.bottom)

        val framed = if (anchor == SpotlightAnchor.REGION) cardColumnOf(target) else target
        val loc = IntArray(2)
        framed.getLocationInWindow(loc)
        return RectF(
            loc[0].toFloat(),
            loc[1].toFloat(),
            (loc[0] + framed.width).toFloat(),
            (loc[1] + framed.height).toFloat()
        )
    }

    /** Climbs from a card to the column holding its sibling cards, just below the ScrollView. */
    private fun cardColumnOf(target: View): View {
        var current: View = target
        while (true) {
            val parent = current.parent as? View ?: return current
            if (parent is ScrollView) return current
            if (parent.parent is ScrollView) return parent
            current = parent
        }
    }

    /**
     * Whether [target] fits entirely between the visible top and bottom. The welcome step
     * uses this to decide whether the screen is tall enough to start in the middle; on a
     * folded or landscape screen the balloon would have no room beside the content.
     */
    private fun fitsOnScreen(target: View): Boolean {
        binding.root.getWindowVisibleDisplayFrame(SCREEN_FRAME)
        val loc = IntArray(2)
        target.getLocationInWindow(loc)
        return loc[1] >= SCREEN_FRAME.top && loc[1] + target.height <= SCREEN_FRAME.bottom
    }

    /** Scrolls the nearest ScrollView ancestor just enough to reveal [target]. */
    private fun scrollIntoView(target: View, visibleTop: Int, visibleBottom: Int) {
        scrollAnimating = false
        val loc = IntArray(2)
        target.getLocationInWindow(loc)
        val clearance = dp(24)
        val delta = when {
            loc[1] + target.height > visibleBottom - clearance ->
                loc[1] + target.height - (visibleBottom - clearance)
            loc[1] < visibleTop + clearance -> loc[1] - (visibleTop + clearance)
            else -> return
        }
        var parent = target.parent
        while (parent != null) {
            if (parent is ScrollView) {
                scrollAnimating = true
                parent.smoothScrollBy(0, delta)
                return
            }
            parent = parent.parent
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        /** ~1.5 s at 60 fps: long enough for a tab switch to inflate, short enough to feel prompt. */
        const val MAX_TARGET_ATTEMPTS = 90
        val SCREEN_FRAME = Rect()
    }
}
