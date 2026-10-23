package com.watermonitor.app.ui.tutorial

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.watermonitor.app.R

/** How a spotlight is derived from its target view. */
enum class SpotlightAnchor {
    /** Frame this single view (with a little air around small controls). */
    STEP,

    /** Frame the group of cards this view sits in, up to the scrolling column. */
    REGION,

    /** There is no target — dim the whole window. Used for the welcome and closing pages. */
    NONE
}

enum class BalloonPlacement {
    /** Prefer below the spotlight, flip above when it does not fit. */
    AUTO,

    /** Prefer above the spotlight, flip below when it does not fit (bottom navigation). */
    PREFER_ABOVE,

    /** Top-of-screen controls such as the gear: prefer below. */
    PREFER_BELOW
}

/**
 * Full-window walkthrough overlay: dims the screen, punches a spotlight over one target,
 * and anchors the explanation balloon to it.
 *
 * Everything here is drawn in code rather than inflated, because the balloon has to be
 * measured against the room actually available above or below the spotlight before it can
 * be positioned — a static layout cannot place itself around a rect it does not know yet.
 *
 * The dim layer is filled and then cut out with PorterDuff `SRC`/`DST_OUT` inside a saved
 * layer, so the spotlight shows the live UI underneath (live sensor values included).
 */
class TutorialOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    fun interface Listener {
        fun onBalloonClick()
    }

    private val balloonContainer = FrameLayout(context)
    private val balloon = MaterialCardView(context)
    private val arrow = ArrowView(context)

    private val iconView = ImageView(context)
    private val stepCounter = TextView(context)
    private val titleView = TextView(context)
    private val bodyView = TextView(context)
    private val skipButton = MaterialButton(context)
    private val actionButton = MaterialButton(context)

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D90A1420")
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.WHITE
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = ContextCompat.getColor(context, R.color.teal_mid)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val spotlightRect = RectF()
    private val scratchRect = RectF()
    private val arrowPath = Path()

    private var hasSpotlight = false
    private var spotlightInset = 0f

    /**
     * Space at the top and bottom of this overlay that the balloon must keep clear. Zero for
     * the current attachment (the overlay fills the nav host, between the bars), but kept as
     * a band so reparenting the overlay under the window would only need a different pair of
     * values instead of a rewrite of the placement maths.
     */
    private var bandTop = 0
    private var bandBottom = 0

    /** Restricts the balloon to this vertical band, in overlay coordinates. */
    fun setUsableBand(top: Int, bottom: Int) {
        bandTop = top.coerceAtLeast(0)
        bandBottom = bottom.coerceAtLeast(0)
    }

    /** True when the balloon is above its arrow (arrow points up from the balloon's top). */
    private var arrowTop = true
    private var arrowCenterX = 0f
    private var arrowHalfWidth = 0f

    /** 0 → balloon entering, 1 → settled. Also fades the spotlight ring in. */
    private var enterProgress = 1f
    private var ringPhase = 0f

    private var balloonClickListener: Listener? = null
    private var actionListener: Listener? = null
    private var skipListener: Listener? = null

    private var enterAnimator: ValueAnimator? = null
    private var ringAnimator: ValueAnimator? = null

    @ColorInt
    private var accentColor: Int = ContextCompat.getColor(context, R.color.teal_deep)

    init {
        // The overlay owns every touch while it is up, so a stray tap on a switch behind
        // the dim cannot half-change the system state mid-tour.
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true

        balloonContainer.isVisible = false
        addView(
            balloonContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        balloonContainer.addView(
            arrow,
            LayoutParams(dp(28f).toInt(), dp(14f).toInt())
        )
        balloonContainer.addView(
            balloon,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
        buildBalloon()
    }

    // ---------------------------------------------------------------- balloon content

    private fun buildBalloon() {
        balloon.strokeWidth = dp(1.5f).toInt()
        balloon.radius = dp(20f)
        balloon.cardElevation = dp(12f)
        balloon.setCardBackgroundColor(Color.parseColor("#F20B2233"))
        balloon.strokeColor = Color.parseColor("#664DB8C8")
        balloon.setOnClickListener { balloonClickListener?.onBalloonClick() }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }

        // Header: a tinted circle holding the step's icon, then "Step 3 of 14".
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconCircle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x26FFFFFF)
            }
        }
        iconView.contentDescription = null
        iconView.setColorFilter(Color.WHITE)
        iconCircle.addView(
            iconView,
            LayoutParams(dp(19f).toInt(), dp(19f).toInt(), Gravity.CENTER)
        )
        headerRow.addView(
            iconCircle,
            LinearLayout.LayoutParams(dp(36f).toInt(), dp(36f).toInt())
        )
        stepCounter.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(0xB3A8E6EF.toInt())
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(11), 0, 0, 0)
        }
        headerRow.addView(
            stepCounter,
            LinearLayout.LayoutParams(0, dp(36f).toInt(), 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        )

        titleView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.01f
        }

        bodyView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTextColor(0xD9FFFFFF.toInt())
            setLineSpacing(dp(3f), 1f)
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        skipButton.apply {
            text = context.getString(R.string.tutorial_skip)
            setTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            backgroundTintList = null
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(4), dp(10), dp(16), dp(10))
            insetTop = 0
            insetBottom = 0
            setOnClickListener { skipListener?.onBalloonClick() }
        }
        actionButton.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            cornerRadius = dp(22).toInt()
            insetTop = 0
            insetBottom = 0
            minWidth = dp(92).toInt()
            minimumWidth = dp(92).toInt()
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { actionListener?.onBalloonClick() }
            // The action button never keeps input focus, so any key — including Back —
            // bubbles back up to this overlay instead of being handled by the button.
            isFocusable = false
        }
        actions.addView(skipButton)
        actions.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(actionButton)

        content.addView(headerRow, LinearLayout.LayoutParams.MATCH_PARENT, dp(36f).toInt())
        content.addView(titleView, matchWidth(topMargin = dp(12f)))
        content.addView(bodyView, matchWidth(topMargin = dp(6f)))
        content.addView(actions, matchWidth(topMargin = dp(12f)))

        balloon.addView(content)
    }

    private fun matchWidth(topMargin: Int) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = topMargin }

    // ---------------------------------------------------------------- public API

    /**
     * Shows [step] over [spotlight] (window coordinates), or dims everything when it is null.
     *
     * @param isLast true when this is the final page — the action button then reads "Done".
     */
    fun showStep(
        step: TutorialStep,
        spotlight: RectF?,
        stepIndex: Int,
        stepCount: Int,
        isLast: Boolean
    ) {
        hasSpotlight = spotlight != null
        spotlightRect.set(spotlight ?: RectF())
        spotlightInset = if (step.anchor == SpotlightAnchor.STEP) dp(6f) else dp(3f)

        accentColor = ContextCompat.getColor(
            context,
            if (isLast) R.color.teal_mid else R.color.teal_deep
        )

        val icon = ContextCompat.getDrawable(context, step.iconRes)
        iconView.setImageDrawable(icon)
        iconView.setColorFilter(Color.WHITE)

        stepCounter.text = if (isLast) {
            context.getString(R.string.tutorial_finish_counter)
        } else {
            context.getString(R.string.tutorial_step_counter, stepIndex + 1, stepCount)
        }
        titleView.text = context.getString(step.titleRes)
        bodyView.text = context.getString(step.bodyRes)
        actionButton.text = context.getString(
            if (isLast) R.string.tutorial_done else R.string.tutorial_next
        )
        skipButton.visibility = if (isLast) View.GONE else View.VISIBLE

        balloonContainer.isVisible = true
        placeBalloon(step.placement)
        // Focus the overlay itself, so both the balloon and Back work: focus behind the dim
        // (a pump switch, a text field) would otherwise keep receiving keys.
        requestFocus()

        enterAnimator?.cancel()
        ringAnimator?.cancel()
        enterAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator(1.6f)
            // `this` inside the lambda is the animator, which has no enterProgress property.
            addUpdateListener { anim ->
                this@TutorialOverlayView.enterProgress = anim.animatedValue as Float
                balloon.alpha = this@TutorialOverlayView.enterProgress
                arrow.alpha = this@TutorialOverlayView.enterProgress
                invalidate()
            }
            start()
        }
        if (hasSpotlight) startRingPulse()
        invalidate()
    }

    fun setOnSkipClickListener(listener: Listener?) {
        skipListener = listener
    }

    fun setOnNextClickListener(listener: Listener?) {
        actionListener = listener
    }

    fun setOnBalloonClickListener(listener: Listener?) {
        balloonClickListener = listener
    }

    /** Cancels animation loops; call before removing the overlay. */
    fun release() {
        ringAnimator?.cancel()
        ringAnimator = null
        enterAnimator?.cancel()
        enterAnimator = null
        balloon.setOnClickListener(null)
    }

    /** True once the overlay has been laid out to its real size. */
    val isSized: Boolean get() = width > 0 && height > 0

    // ---------------------------------------------------------------- placement

    private fun placeBalloon(placement: BalloonPlacement) {
        val margin = dp(16f)
        val gap = dp(14f)
        val arrowHeight = dp(14f)

        val availableWidth = (width - 2 * margin).toInt().coerceAtLeast(dp(1f).toInt())
        val maxBalloonWidth = minOf(dp(340f).toInt(), availableWidth)
        // Cap the balloon's height at what is available between the reserved bands, so a long
        // step on a short screen scrolls inside the card rather than pushing "Next" under
        // the bottom navigation. (The un-spotlighted welcome card is centred in the overlay,
        // which already sits between the bars.)
        val usableHeight = (height - bandTop - bandBottom - 2 * margin).toInt()
            .coerceAtLeast(dp(1f).toInt())
        balloon.measure(
            MeasureSpec.makeMeasureSpec(maxBalloonWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(usableHeight, MeasureSpec.AT_MOST)
        )
        val w = balloon.measuredWidth
        val h = balloon.measuredHeight
        val balloonParams = balloon.layoutParams as LayoutParams
        val arrowParams = arrow.layoutParams as LayoutParams

        if (!hasSpotlight || width == 0 || height == 0) {
            arrow.isVisible = false
            balloonParams.gravity = Gravity.CENTER
            balloonParams.leftMargin = 0
            balloonParams.topMargin = 0
            balloon.layoutParams = balloonParams
            return
        }

        // Usable vertical band (the overlay spans the nav host, between the bars).
        val topLimit = bandTop + margin
        val bottomLimit = height - bandBottom - margin

        // Pick a side: honour the step's preference when it fits, otherwise the roomier side.
        val roomBelow = bottomLimit - (spotlightRect.bottom + gap + arrowHeight * 0.5f)
        val roomAbove = spotlightRect.top - gap - arrowHeight * 0.5f - topLimit
        val preferBelow = placement != BalloonPlacement.PREFER_ABOVE
        val below = when {
            preferBelow && roomBelow >= h -> true
            !preferBelow && roomAbove >= h -> false
            else -> roomBelow >= roomAbove
        }

        // Horizontal: centre on the spotlight, clamped inside the margins.
        val desiredX = spotlightRect.centerX() - w / 2f
        val x = desiredX.coerceIn(margin, (width - margin - w).coerceAtLeast(margin))

        // Arrow: point at the spotlight centre, but stay within the balloon's soft corners.
        arrowCenterX = spotlightRect.centerX().coerceIn(x + dp(34f), (x + w - dp(34f)).coerceAtLeast(x + dp(34f)))
        arrowHalfWidth = dp(13f)
        arrowTop = below

        // Both ends are clamped. If neither side had full room the balloon overlaps the
        // spotlight slightly rather than leaving the visible band.
        val (top, arrowTopY) = if (below) {
            val balloonTop = (spotlightRect.bottom + gap)
                .coerceIn(topLimit, (bottomLimit - h).coerceAtLeast(topLimit))
            balloonTop to (balloonTop - arrowHeight + 1f)
        } else {
            val balloonTop = (spotlightRect.top - gap - h)
                .coerceIn(topLimit, (bottomLimit - h).coerceAtLeast(topLimit))
            balloonTop to (balloonTop + h - 1f)
        }

        balloonParams.gravity = Gravity.TOP or Gravity.START
        balloonParams.leftMargin = x.toInt()
        balloonParams.topMargin = top.toInt()
        balloon.layoutParams = balloonParams

        arrowParams.gravity = Gravity.TOP or Gravity.START
        arrowParams.leftMargin = (arrowCenterX - arrowHalfWidth).toInt()
        arrowParams.topMargin = arrowTopY.toInt()
        arrow.layoutParams = arrowParams
        arrow.isVisible = true

        balloonContainer.bringChildToFront(arrow)
        balloonContainer.bringChildToFront(balloon)
    }

    // ---------------------------------------------------------------- ring pulse

    private fun startRingPulse() {
        ringAnimator?.cancel()
        ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                this@TutorialOverlayView.ringPhase = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        if (hasSpotlight) {
            cutoutRect(scratchRect)
            val radius = cutoutRadius(scratchRect)
            canvas.drawRoundRect(scratchRect, radius, radius, clearPaint)

            if (arrow.isVisible) {
                // Keep a clean page under the arrow too.
                scratchRect.set(
                    arrow.left - dp(1f),
                    arrow.top,
                    arrow.right + dp(1f),
                    arrow.bottom
                )
                canvas.drawRect(scratchRect, clearPaint)
            }
        }
        canvas.restoreToCount(layer)

        if (hasSpotlight) {
            cutoutRect(scratchRect)
            val radius = cutoutRadius(scratchRect)

            framePaint.alpha = (255 * enterProgress).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(scratchRect, radius, radius, framePaint)

            val expand = dp(16f) * ringPhase
            ringPaint.alpha = ((1f - ringPhase) * 140).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(
                scratchRect.left - expand,
                scratchRect.top - expand,
                scratchRect.right + expand,
                scratchRect.bottom + expand,
                radius + expand,
                radius + expand,
                ringPaint
            )
        }
    }

    /** Spotlight rect including its breathing room. */
    private fun cutoutRect(out: RectF) {
        out.set(spotlightRect)
        out.inset(-spotlightInset, -spotlightInset)
    }

    /**
     * Compact, squarish targets (nav tabs, the gear) get a gentler corner than tall cards,
     * so the frame hugs a round-ish control instead of turning it into a pill.
     */
    private fun cutoutRadius(rect: RectF): Float =
        if (rect.width() < rect.height() * 1.5f) dp(14f) else dp(18f)

    /** Small triangle tying the balloon to the spotlight. */
    private inner class ArrowView(context: Context) : View(context) {
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            arrowPath.reset()
            if (arrowTop) {
                // Balloon is below the spotlight; the arrow points up at it.
                arrowPath.moveTo(0f, h)
                arrowPath.lineTo(w / 2f, 0f)
                arrowPath.lineTo(w, h)
            } else {
                arrowPath.moveTo(0f, 0f)
                arrowPath.lineTo(w / 2f, h)
                arrowPath.lineTo(w, 0f)
            }
            arrowPath.close()
            arrowPaint.color = accentColor
            arrowPaint.alpha = (255 * enterProgress).toInt().coerceIn(0, 255)
            canvas.drawPath(arrowPath, arrowPaint)
        }
    }

    // ---------------------------------------------------------------- touch & back

    override fun onTouchEvent(event: MotionEvent): Boolean = true

    /**
     * Key handling needs focus on this overlay (see [showStep], which requests it). It is
     * attached to the activity's root ConstraintLayout, which is not itself focusable.
     */
    fun setOnBackPressedListener(listener: Listener?) {
        // onKeyListener, not dispatchKeyEvent: the latter also intercepts volume, media and
        // remote-control keys, which a back handler must not swallow.
        setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                listener?.onBalloonClick()
                true
            } else {
                false
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
