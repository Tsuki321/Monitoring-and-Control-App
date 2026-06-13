package com.watermonitor.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.firebase.auth.FirebaseAuth
import com.watermonitor.app.databinding.ActivityMainBinding
import com.watermonitor.app.utils.LocaleHelper
import com.watermonitor.app.utils.ThemeHelper
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

    private var backPressedOnce = false
    private val backPressHandler = Handler(Looper.getMainLooper())

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(ThemeHelper.getSavedThemeMode(this))
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
        setupBackPressHandler()
        updateClock()
        clockHandler.postDelayed(clockRunnable, 30_000)
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        val navController = navHostFragment.navController

        // Check if user is already logged in
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            // User is logged in, navigate to dashboard
            navController.navigate(R.id.dashboardFragment)
        }

        binding.bottomNav.setupWithNavController(navController)

        // Update title, bottom nav visibility, and top-bar button based on destination
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.tvTitle.text = when (destination.id) {
                R.id.dashboardFragment -> getString(R.string.title_dashboard)
                R.id.monitoringFragment -> getString(R.string.title_monitoring)
                R.id.controlFragment -> getString(R.string.title_control)
                R.id.settingsFragment -> getString(R.string.title_settings)
                R.id.aboutFragment -> getString(R.string.title_about)
                R.id.loginFragment -> getString(R.string.title_login)
                R.id.registerFragment -> getString(R.string.title_register)
                else -> getString(R.string.app_name)
            }

            // Hide bottom nav on Settings, About, and Auth pages
            val isSecondaryPage = destination.id == R.id.settingsFragment ||
                    destination.id == R.id.aboutFragment
            val isAuthPage = destination.id == R.id.loginFragment ||
                    destination.id == R.id.registerFragment

            binding.bottomNav.visibility = if (isSecondaryPage || isAuthPage) View.GONE else View.VISIBLE

            // Hide top bar on Auth pages
            binding.topBar.visibility = if (isAuthPage) View.GONE else View.VISIBLE

            // Show back arrow on secondary pages, gear icon on main pages
            binding.btnSettings.setImageResource(
                if (isSecondaryPage) R.drawable.ic_back else R.drawable.ic_gear
            )
        }

        // Navigate to Settings (from main pages) or navigate up (from secondary pages)
        binding.btnSettings.setOnClickListener {
            val currentId = navController.currentDestination?.id
            if (currentId == R.id.settingsFragment || currentId == R.id.aboutFragment) {
                navController.navigateUp()
            } else {
                navController.navigate(R.id.settingsFragment)
            }
        }
    }

    private fun setupBackPressHandler() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        val navController = navHostFragment.navController

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentDestination = navController.currentDestination?.id

                // If on main screens (Dashboard, Monitoring, Control), handle double-tap to exit
                if (currentDestination == R.id.dashboardFragment ||
                    currentDestination == R.id.monitoringFragment ||
                    currentDestination == R.id.controlFragment) {

                    if (backPressedOnce) {
                        // Second press - exit app
                        finish()
                        return
                    }

                    // First press - show toast
                    backPressedOnce = true
                    Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()

                    // Reset after 2 seconds
                    backPressHandler.postDelayed({
                        backPressedOnce = false
                    }, 2000)
                } else {
                    // For other screens, use default navigation behavior
                    if (!navController.navigateUp()) {
                        finish()
                    }
                }
            }
        })
    }

    private fun updateClock() {
        val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        binding.tvDateTime.text = formatter.format(Date())
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockRunnable)
        backPressHandler.removeCallbacksAndMessages(null)
    }
}

