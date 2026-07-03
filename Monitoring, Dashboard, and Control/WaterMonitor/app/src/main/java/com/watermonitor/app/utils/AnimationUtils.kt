package com.watermonitor.app.utils

import android.animation.ArgbEvaluator
import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import kotlin.math.roundToInt

object AnimationUtils {

    private val TAG_TEXT_COUNT = View.generateViewId()
    private val TAG_TEXT_COLOR = View.generateViewId()

    /**
     * Animates a TextView's numeric content from [from] to [to],
     * appending [suffix] after the number. Shows one decimal place for doubles.
     * Cancels any prior count-up animator on the same view before starting.
     */
    fun animateTextCount(
        textView: TextView,
        from: Double,
        to: Double,
        suffix: String = "",
        decimals: Int = 0,
        durationMs: Long = 600
    ) {
        // Cancel any prior animator on this TextView to prevent overlapping
        (textView.getTag(TAG_TEXT_COUNT) as? ValueAnimator)?.cancel()
        ValueAnimator.ofFloat(from.toFloat(), to.toFloat()).apply {
            textView.setTag(TAG_TEXT_COUNT, this)
            duration = durationMs
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { anim ->
                val value = anim.animatedValue as Float
                textView.text = when (decimals) {
                    0 -> "${value.roundToInt()}$suffix"
                    1 -> "${"%.1f".format(value)}$suffix"
                    2 -> "${"%.2f".format(value)}$suffix"
                    else -> "${"%.${decimals}f".format(value)}$suffix"
                }
            }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationEnd(animation: Animator) {
                    if (textView.getTag(TAG_TEXT_COUNT) === this@apply) {
                        textView.setTag(TAG_TEXT_COUNT, null)
                    }
                }
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
            })
        }.start()
    }

    /**
     * Staggers slide-up + fade-in entrance animation across a list of views.
     * Each view animates [delayMs] milliseconds after the previous.
     * Enhanced with subtle scale for more refined feel.
     */
    fun animateCardEntrance(views: List<View>, delayMs: Long = 100) {
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 60f
            view.scaleX = 0.95f
            view.scaleY = 0.95f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(500)
                .setStartDelay(index * delayMs)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        }
    }

    /**
     * Single scale pulse: grows to [scalePeak] then returns to 1.0.
     * Enhanced with smoother interpolation.
     */
    fun pulseView(view: View, scalePeak: Float = 1.04f, durationMs: Long = 200) {
        view.animate()
            .scaleX(scalePeak)
            .scaleY(scalePeak)
            .setDuration(durationMs / 2)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(durationMs / 2)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /**
     * Continuous slow rotation, e.g. for an active pump icon.
     * Returns the [Animator] so the caller can cancel it when the pump turns off.
     */
    fun startRotation(view: View, durationMs: Long = 2400): Animator {
        return ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            start()
        }
    }

    /**
     * Stops a running rotation animator (if any) and eases the view back to 0°.
     */
    fun stopRotation(animator: Animator?, view: View) {
        animator?.cancel()
        view.animate()
            .rotation(0f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * Gentle breathing pulse for an "online" status dot.
     * Returns the [Animator] so the caller can cancel it when the dot goes offline.
     */
    fun startBreathingPulse(view: View): Animator {
        return ValueAnimator.ofFloat(1f, 1.18f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                view.scaleX = scale
                view.scaleY = scale
            }
            start()
        }
    }

    /**
     * Stops a running breathing pulse and resets the view to its resting scale.
     */
    fun stopBreathingPulse(animator: Animator?, view: View) {
        animator?.cancel()
        view.scaleX = 1f
        view.scaleY = 1f
    }

    /**
     * Tweens a TextView's color from [fromColor] to [toColor] over a short interval.
     * Used when a reading crosses into a new status band so the change is felt, not just shown.
     * Cancels any prior color animator on the same view before starting.
     */
    fun transitionTextColor(textView: TextView, fromColor: Int, toColor: Int) {
        (textView.getTag(TAG_TEXT_COLOR) as? ValueAnimator)?.cancel()
        ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor).apply {
            textView.setTag(TAG_TEXT_COLOR, this)
            duration = 600
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { anim ->
                textView.setTextColor(anim.animatedValue as Int)
            }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationEnd(animation: Animator) {
                    if (textView.getTag(TAG_TEXT_COLOR) === this@apply) {
                        textView.setTag(TAG_TEXT_COLOR, null)
                    }
                }
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
            })
        }.start()
    }
}
