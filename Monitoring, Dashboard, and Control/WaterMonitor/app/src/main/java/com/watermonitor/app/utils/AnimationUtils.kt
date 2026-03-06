package com.watermonitor.app.utils

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import kotlin.math.roundToInt

object AnimationUtils {

    /**
     * Animates a TextView's numeric content from [from] to [to],
     * appending [suffix] after the number. Shows one decimal place for doubles.
     */
    fun animateTextCount(
        textView: TextView,
        from: Double,
        to: Double,
        suffix: String = "",
        decimals: Int = 0,
        durationMs: Long = 600
    ) {
        ValueAnimator.ofFloat(from.toFloat(), to.toFloat()).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val value = anim.animatedValue as Float
                textView.text = when (decimals) {
                    0 -> "${value.roundToInt()}$suffix"
                    1 -> "${"%.1f".format(value)}$suffix"
                    2 -> "${"%.2f".format(value)}$suffix"
                    else -> "${"%.${decimals}f".format(value)}$suffix"
                }
            }
        }.start()
    }

    /**
     * Staggers slide-up + fade-in entrance animation across a list of views.
     * Each view animates [delayMs] milliseconds after the previous.
     */
    fun animateCardEntrance(views: List<View>, delayMs: Long = 100) {
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 80f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(450)
                .setStartDelay(index * delayMs)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /**
     * Single scale pulse: grows to [scalePeak] then returns to 1.0.
     */
    fun pulseView(view: View, scalePeak: Float = 1.05f, durationMs: Long = 200) {
        view.animate()
            .scaleX(scalePeak)
            .scaleY(scalePeak)
            .setDuration(durationMs / 2)
            .setInterpolator(OvershootInterpolator())
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
     * Card press-down effect: scale slightly down on press, restore on release.
     * Use with setOnTouchListener.
     */
    fun animatePressDown(view: View) {
        view.animate()
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(80)
            .start()
    }

    fun animatePressUp(view: View) {
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(120)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    /**
     * Fades in a view from invisible to fully visible.
     */
    fun fadeIn(view: View, durationMs: Long = 350) {
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
