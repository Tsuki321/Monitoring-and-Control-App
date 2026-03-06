package com.watermonitor.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.watermonitor.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge rendering
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply window insets so top/bottom bars don't overlap system UI
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                systemBars.top,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                systemBars.bottom
            )
            insets
        }

        setupNavigation()
        setupTopBar()
        updateClock()
        clockHandler.postDelayed(clockRunnable, 30_000)
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNav.setupWithNavController(navController)

        // Update title based on destination
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.tvTitle.text = when (destination.id) {
                R.id.dashboardFragment -> getString(R.string.title_dashboard)
                R.id.monitoringFragment -> getString(R.string.title_monitoring)
                R.id.controlFragment -> getString(R.string.title_control)
                else -> getString(R.string.app_name)
            }
        }
    }

    private fun setupTopBar() {
        binding.btnSettings.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setMessage(R.string.settings_placeholder)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun updateClock() {
        val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        binding.tvDateTime.text = formatter.format(Date())
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockRunnable)
    }
}
