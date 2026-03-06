package com.watermonitor.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewPropertyAnimator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.watermonitor.app.databinding.ActivitySplashBinding

/**
 * Entry-point splash screen that plays an animated water-drop SVG, then
 * fades smoothly into [MainActivity].
 *
 * Timeline:
 *   0 ms  – drop scales in (AnimatedVectorDrawable starts)
 *   500 ms – app-name fades in
 *   800 ms – tagline fades in
 *   2400 ms – whole screen starts fading out
 *   2900 ms – MainActivity launched; splash finishes
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    /** Total time the splash is visible before the transition begins. */
    private val splashHoldMs = 2400L

    /** Duration of the fade-out transition. */
    private val fadeOutMs = 500L

    private val handler = Handler(Looper.getMainLooper())

    /** Tracks running property animators so they can be cancelled on destroy. */
    private val runningAnimators = mutableListOf<ViewPropertyAnimator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startDropAnimation()
        fadeInText()

        handler.postDelayed({ fadeOutAndLaunch() }, splashHoldMs)
    }

    /** Starts the AnimatedVectorDrawable on the water-drop ImageView. */
    private fun startDropAnimation() {
        val drawable = binding.splashDropIcon.drawable
        if (drawable is Animatable) {
            drawable.start()
        }
    }

    /** Fades in the app name and tagline with a small stagger. */
    private fun fadeInText() {
        with(binding.splashAppName) {
            alpha = 0f
            animate()
                .alpha(1f)
                .setStartDelay(500)
                .setDuration(600)
                .also { runningAnimators += it }
                .start()
        }
        with(binding.splashTagline) {
            alpha = 0f
            animate()
                .alpha(1f)
                .setStartDelay(800)
                .setDuration(600)
                .also { runningAnimators += it }
                .start()
        }
    }

    /** Fades out the entire splash screen, then starts [MainActivity]. */
    private fun fadeOutAndLaunch() {
        binding.splashRoot
            .animate()
            .alpha(0f)
            .setDuration(fadeOutMs)
            .also { runningAnimators += it }
            .withEndAction {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                // Suppress the default activity transition; the fade-out above
                // already provides a smooth visual handoff.
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
            .start()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        runningAnimators.forEach { it.cancel() }
        runningAnimators.clear()
    }
}
