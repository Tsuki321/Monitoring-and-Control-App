package com.watermonitor.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.NavOptions
import androidx.navigation.ui.setupWithNavController
import androidx.lifecycle.Lifecycle
import com.google.firebase.auth.FirebaseAuth
import com.watermonitor.app.data.repository.FilterHealthRepository
import com.watermonitor.app.databinding.ActivityMainBinding
import com.watermonitor.app.notifications.TankAlertPreferences
import com.watermonitor.app.notifications.TankMonitoringService
import com.watermonitor.app.notifications.TankNotifications
import com.watermonitor.app.utils.LocaleHelper
import com.watermonitor.app.ui.legal.LegalDialog
import com.watermonitor.app.ui.tutorial.TutorialController
import com.watermonitor.app.utils.LegalConsentManager
import com.watermonitor.app.utils.ThemeHelper
import com.watermonitor.app.utils.TutorialPrefs
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
    private var legalDialogShowing = false
    private var alertSetupDialog: AlertDialog? = null
    private var alertPermissionPending = false
    private val tankAlertPrefs by lazy { TankAlertPreferences(this) }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        alertPermissionPending = false
        if (granted) {
            binding.root.post { syncTankMonitoring() }
        } else {
            showBlockedNotifications()
        }
    }

    /** Guided tour; created once the nav controller exists, from setupNavigation(). */
    private var tutorial: TutorialController? = null

    companion object {
        /**
         * Set on the activity's intent just before Register navigates to the Dashboard, so
         * an account that has just been created gets the tour on the first Dashboard it
         * sees. Consumed (and removed) once in onCreate; later recreations rely on the
         * saved instance state instead.
         */
        const val EXTRA_START_TUTORIAL = "extra_start_tutorial"
        private const val STATE_PENDING_TUTORIAL = "pending_tutorial"
        private const val STATE_TOUR_COMPLETED_ON_LAUNCH = "tour_completed_on_launch"
        private const val STATE_TOUR_SHOWING = "tour_showing"
    }

    private var pendingTutorialStart = false

    /**
     * Whether the tour had already been finished when this activity was first created.
     * A replay is an explicit request, so it survives a recreate; but a request that was
     * never acted on (the user left Settings straight away) is not restored on a later
     * launch, which is what keeps the tour once-per-account for someone who completed it.
     */
    private var completedTourOnLaunch = false

    /** Whether the tour overlay was up when the view state was last saved. */
    private var tourWasShowingOnSave = false


    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(ThemeHelper.getSavedThemeMode(this))
        super.onCreate(savedInstanceState)

        // A fresh start reads the request from Register; a recreate keeps its own flag so a
        // theme/locale change mid-tour does not silently cancel it. Only the fresh-start read
        // consumes the extra — clearing it on a recreate as well would wipe the request
        // before setupNavigation() looks at it.
        completedTourOnLaunch = savedInstanceState?.getBoolean(STATE_TOUR_COMPLETED_ON_LAUNCH, false)
            ?: FirebaseAuth.getInstance().currentUser?.uid
                ?.let { TutorialPrefs.hasCompleted(this, it) } == true
        pendingTutorialStart = if (savedInstanceState != null) {
            savedInstanceState.getBoolean(STATE_PENDING_TUTORIAL, false)
        } else {
            val requested = intent?.getBooleanExtra(EXTRA_START_TUTORIAL, false) == true
            intent?.removeExtra(EXTRA_START_TUTORIAL)
            requested
        }
        tourWasShowingOnSave = savedInstanceState?.getBoolean(STATE_TOUR_SHOWING, false) == true

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
        window.decorView.post { enforceLegalConsent() }
    }

    override fun onResume() {
        super.onResume()
        enforceLegalConsent()
        binding.root.post {
            if (!isFinishing && !isDestroyed) syncTankMonitoring()
        }
    }

    private fun syncTankMonitoring() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            TankMonitoringService.stop(this)
            return
        }
        val navHost = supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment
        val destination = navHost?.navController?.currentDestination?.id
        if (destination == null || destination == R.id.loginFragment ||
            destination == R.id.registerFragment || legalDialogShowing ||
            !LegalConsentManager.hasCurrentConsent(this, uid)) return
        TankNotifications.createChannels(this)
        if (alertSetupDialog?.isShowing == true || alertPermissionPending) return
        if (tankAlertPrefs.setupVersion != BuildConfig.VERSION_CODE) {
            showAlertSetup()
            return
        }
        if (!tankAlertPrefs.enabled) {
            TankMonitoringService.stop(this)
            startTutorialWhenReady()
            return
        }
        if (TankNotifications.canMonitor(this)) {
            TankMonitoringService.start(this, fromVisibleActivity = true)
        } else {
            TankMonitoringService.stop(this)
        }
        startTutorialWhenReady()
    }

    private fun showAlertSetup() {
        if (alertSetupDialog?.isShowing == true) return
        alertSetupDialog = AlertDialog.Builder(this)
            .setTitle(R.string.alert_setup_title)
            .setMessage(R.string.alert_setup_message)
            .setCancelable(false)
            .setPositiveButton(R.string.alert_setup_enable) { _, _ ->
                tankAlertPrefs.setupVersion = BuildConfig.VERSION_CODE
                tankAlertPrefs.enabled = true
                requestAlertPermission()
            }
            .setNegativeButton(R.string.alert_setup_later) { _, _ ->
                tankAlertPrefs.setupVersion = BuildConfig.VERSION_CODE
                syncTankMonitoring()
            }
            .create().also { dialog ->
                dialog.setOnDismissListener {
                    if (alertSetupDialog === dialog) alertSetupDialog = null
                    binding.root.post { if (!isFinishing && !isDestroyed) syncTankMonitoring() }
                }
                dialog.show()
            }
    }

    private fun requestAlertPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {
            alertPermissionPending = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!TankNotifications.canNotify(this, TankNotifications.ALERT_CHANNEL) ||
            !TankNotifications.canNotify(this, TankNotifications.LEAK_CHANNEL)) {
            showBlockedNotifications()
        } else {
            syncTankMonitoring()
        }
    }

    private fun showBlockedNotifications() {
        if (isFinishing || isDestroyed) return
        alertSetupDialog = AlertDialog.Builder(this)
            .setTitle(R.string.alert_setup_title)
            .setMessage(R.string.tank_alert_permission_denied)
            .setPositiveButton(R.string.tank_notification_settings) { _, _ ->
                openTankNotificationSettings()
            }
            .setNegativeButton(R.string.alert_setup_later, null)
            .create().also { dialog ->
                dialog.setOnDismissListener {
                    if (alertSetupDialog === dialog) alertSetupDialog = null
                    binding.root.post { if (!isFinishing && !isDestroyed) syncTankMonitoring() }
                }
                dialog.show()
            }
    }

    fun setTankAlertsEnabled(enabled: Boolean) {
        tankAlertPrefs.enabled = enabled
        if (enabled) {
            tankAlertPrefs.setupVersion = BuildConfig.VERSION_CODE
            requestAlertPermission()
        } else {
            TankMonitoringService.stop(this)
        }
    }

    fun openTankNotificationSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Carry the tour across a recreate so a theme/locale change or process death midway
        // does not silently drop it. Only for an account that has never finished it: after
        // "Skip"/"Done" the flag is written and the tour is not resurrected; for a returning
        // user replaying from Settings and then leaving, it is simply not restored, which
        // keeps the once-only promise intact.
        outState.putBoolean(STATE_PENDING_TUTORIAL, pendingTutorialStart)
        outState.putBoolean(STATE_TOUR_COMPLETED_ON_LAUNCH, completedTourOnLaunch)
        outState.putBoolean(STATE_TOUR_SHOWING, tutorial?.isShowing == true)
    }

    /**
     * Settings → "Replay tour". The tour is driven from the activity (it owns the overlay,
     * the binding and the nav controller), so the fragment reaches it through its host.
     */
    fun replayTutorial() {
        pendingTutorialStart = true
        startTutorialWhenReady()
    }

    private fun startTutorialWhenReady() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            legalDialogShowing || alertSetupDialog?.isShowing == true || alertPermissionPending ||
            tankAlertPrefs.setupVersion != BuildConfig.VERSION_CODE ||
            !LegalConsentManager.hasCurrentConsent(this, uid) || tutorial?.isShowing == true) return
        if (pendingTutorialStart || !TutorialPrefs.hasCompleted(this, uid)) {
            pendingTutorialStart = false
            tutorial?.start()
        }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        val navController = navHostFragment.navController

        // Check if user is already logged in. Only auto-navigate on a fresh start
        // (when the start destination is loginFragment). On activity recreate the
        // nav state is already restored to wherever the user was, so re-navigating
        // would crash because the login→dashboard action doesn't exist from other
        // destinations (e.g. settingsFragment).
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null && navController.currentDestination?.id == R.id.loginFragment) {
            navController.navigate(R.id.action_loginFragment_to_dashboardFragment)
        }

        binding.bottomNav.setupWithNavController(navController)

        val controller = TutorialController(this, binding, navController) {
            // Nothing to tear down here: the controller removes its own overlay. Recorded so
            // a later save no longer treats the tour as pending.
            tourWasShowingOnSave = false
            pendingTutorialStart = false
        }
        tutorial = controller
        // Offer the tour once per account. A newly registered account asks for it from the
        // Register screen (the intent extra); anyone else who has never finished it —
        // including accounts that existed before this feature — sees it on their next launch.
        val explicitlyRequested = pendingTutorialStart
        val neverCompleted = currentUser != null && !TutorialPrefs.hasCompleted(this, currentUser.uid)
        // On a recreate, only resume a tour that was actually on screen when the state was
        // saved (the overlay itself is not recreated by the framework). A request that was
        // never acted on — the user tapped Replay and immediately left Settings — is
        // dropped, so it cannot reappear on a later launch.
        val resumeShowingTour = tourWasShowingOnSave
        if (resumeShowingTour || explicitlyRequested || neverCompleted) {
            pendingTutorialStart = true
            // Consent and notification setup run first; syncTankMonitoring starts the tour.
        }

        // Update title, bottom nav visibility, and top-bar button based on destination
        navController.addOnDestinationChangedListener { _, destination, _ ->
            tutorial?.onDestinationChanged(destination.id)

            binding.tvTitle.text = when (destination.id) {
                R.id.dashboardFragment -> getString(R.string.title_dashboard)
                R.id.monitoringFragment -> getString(R.string.title_monitoring)
                R.id.controlFragment -> getString(R.string.title_control)
                R.id.filterFragment -> getString(R.string.title_filter)
                R.id.historyFragment -> getString(R.string.history_title)
                R.id.settingsFragment -> getString(R.string.title_settings)
                R.id.aboutFragment -> getString(R.string.title_about)
                R.id.loginFragment -> getString(R.string.title_login)
                R.id.registerFragment -> getString(R.string.title_register)
                else -> getString(R.string.app_name)
            }

            // Hide bottom nav on Settings, About, Filter, and Auth pages
            val isSecondaryPage = destination.id == R.id.settingsFragment ||
                    destination.id == R.id.aboutFragment ||
                    destination.id == R.id.filterFragment
            val isAuthPage = destination.id == R.id.loginFragment ||
                    destination.id == R.id.registerFragment

            binding.bottomNav.visibility = if (isSecondaryPage || isAuthPage) View.GONE else View.VISIBLE

            // Hide top bar on Auth pages
            binding.topBar.visibility = if (isAuthPage) View.GONE else View.VISIBLE

            // Show back arrow on secondary pages, gear icon on main pages
            binding.btnSettings.setImageResource(
                if (isSecondaryPage) R.drawable.ic_back else R.drawable.ic_gear
            )
            binding.btnSettings.contentDescription = getString(
                if (isSecondaryPage) R.string.navigate_up else R.string.settings
            )
            binding.root.post {
                if (!isFinishing && !isDestroyed) {
                    enforceLegalConsent()
                    syncTankMonitoring()
                }
            }
        }

        // Navigate to Settings (from main pages) or navigate up (from secondary pages).
        // This list must stay in step with isSecondaryPage above, or a secondary page shows
        // a back arrow that navigates forward into Settings.
        binding.btnSettings.setOnClickListener {
            val currentId = navController.currentDestination?.id
            if (currentId == R.id.settingsFragment ||
                currentId == R.id.aboutFragment ||
                currentId == R.id.filterFragment
            ) {
                navController.navigateUp()
            } else {
                navController.navigate(R.id.settingsFragment)
            }
        }
    }

    private fun enforceLegalConsent() {
        if (!::binding.isInitialized || legalDialogShowing) return

        val user = FirebaseAuth.getInstance().currentUser ?: return
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as? NavHostFragment ?: return
        val navController = navHostFragment.navController
        val destination = navController.currentDestination?.id ?: return

        // Authentication screens own their own registration/login consent flow.
        if (destination == R.id.loginFragment || destination == R.id.registerFragment) return
        if (LegalConsentManager.hasCurrentConsent(this, user.uid)) return

        legalDialogShowing = true
        LegalDialog.showExistingUserConsent(
            context = this,
            onAccepted = {
                LegalConsentManager.recordConsent(this, user.uid)
                legalDialogShowing = false
                syncTankMonitoring()
            },
            onDeclined = {
                legalDialogShowing = false
                FirebaseAuth.getInstance().signOut()
                navController.navigate(
                    R.id.loginFragment,
                    null,
                    NavOptions.Builder()
                        .setPopUpTo(R.id.nav_graph, true)
                        .build()
                )
            }
        )
    }

    private fun setupBackPressHandler() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        val navController = navHostFragment.navController

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tutorial?.isShowing == true) {
                    tutorial?.finish()
                    return
                }
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

    override fun onStop() {
        super.onStop()
        // Commit accrued filter wear once per foreground session. The repository throttles
        // writes to one a minute, so without this the last stretch before backgrounding
        // would be lost if the process were killed.
        FilterHealthRepository.flush()
    }

    override fun onDestroy() {
        tutorial?.dispose()
        alertSetupDialog?.dismiss()
        alertSetupDialog = null
        super.onDestroy()
        clockHandler.removeCallbacks(clockRunnable)
        backPressHandler.removeCallbacksAndMessages(null)
    }
}

