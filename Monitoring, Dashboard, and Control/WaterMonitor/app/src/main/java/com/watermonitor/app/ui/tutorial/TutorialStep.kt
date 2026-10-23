package com.watermonitor.app.ui.tutorial

import com.watermonitor.app.R

/** Which destination a tour step needs on screen before it can be shown. */
enum class TutorialDestination {
    DASHBOARD,
    MONITORING,
    CONTROL
}

/**
 * The view a step points at.
 *
 * These resolve to view IDs in the fragment and activity layouts, so the tour spotlights the
 * real UI the user will operate rather than a picture of it. A step whose view is not on
 * screen (a card scrolled out of reach, a control hidden by a banner) is skipped, so a step
 * whose view is hidden before its step in the tour never blocks the rest of that tab.
 */
enum class TutorialTarget {
    TUTORIAL_HEADER,
    TAB_HOME,
    TAB_MONITORING,
    TAB_CONTROL,
    SETTINGS_BUTTON,
    DASHBOARD_SAFETY_CARD,
    DASHBOARD_SYSTEM_CARD,
    DASHBOARD_SENSOR_CARD,
    DASHBOARD_FILTER_CARD,
    MONITORING_TANK_CARD,
    MONITORING_PH_CARD,
    CONTROL_MODE_CARD,
    CONTROL_PUMP_A_CARD
}

/**
 * One page of the guided tour.
 *
 * [placement] is only a preference: the overlay flips the balloon to the other side when
 * there is not enough room, so a step still renders on short screens.
 */
data class TutorialStep(
    val id: String,
    val destination: TutorialDestination,
    val target: TutorialTarget,
    val anchor: SpotlightAnchor,
    val iconRes: Int,
    val titleRes: Int,
    val bodyRes: Int,
    val placement: BalloonPlacement = BalloonPlacement.AUTO
) {
    companion object {
        /**
         * The tour, in order. It follows the path the user actually takes: the Dashboard,
         * then each bottom-nav tab left to right, with the gear explained from Control
         * before returning home to finish.
         */
        val DEFAULT: List<TutorialStep> = listOf(
            TutorialStep(
                id = "welcome",
                destination = TutorialDestination.DASHBOARD,
                target = TutorialTarget.TUTORIAL_HEADER,
                anchor = SpotlightAnchor.NONE,
                iconRes = R.drawable.ic_water_drop_splash,
                titleRes = R.string.tutorial_welcome_title,
                bodyRes = R.string.tutorial_welcome_body
            ),
            TutorialStep(
                id = "bottom_nav",
                destination = TutorialDestination.DASHBOARD,
                target = TutorialTarget.TAB_HOME,
                anchor = SpotlightAnchor.STEP,
                iconRes = R.drawable.ic_home,
                titleRes = R.string.tutorial_nav_title,
                bodyRes = R.string.tutorial_nav_body,
                placement = BalloonPlacement.PREFER_ABOVE
            ),
            TutorialStep(
                id = "dashboard_safety",
                destination = TutorialDestination.DASHBOARD,
                target = TutorialTarget.DASHBOARD_SAFETY_CARD,
                anchor = SpotlightAnchor.STEP,
                iconRes = R.drawable.ic_status_dot,
                titleRes = R.string.tutorial_safety_title,
                bodyRes = R.string.tutorial_safety_body,
                placement = BalloonPlacement.PREFER_BELOW
            ),
            TutorialStep(
                id = "dashboard_system",
                destination = TutorialDestination.DASHBOARD,
                target = TutorialTarget.DASHBOARD_SYSTEM_CARD,
                anchor = SpotlightAnchor.STEP,
                iconRes = R.drawable.ic_pump,
                titleRes = R.string.tutorial_system_title,
                bodyRes = R.string.tutorial_system_body,
                placement = BalloonPlacement.PREFER_BELOW
            ),
            TutorialStep(
                id = "dashboard_sensors",
                destination = TutorialDestination.DASHBOARD,
                target = TutorialTarget.DASHBOARD_SENSOR_CARD,
                anchor = SpotlightAnchor.STEP,
                iconRes = R.drawable.ic_wave,
                titleRes = R.string.tutorial_sensors_title,
                bodyRes = R.string.tutorial_sensors_body,
                placement = BalloonPlacement.PREFER_BELOW
            ),
            TutorialStep(
                id = "dashboard_filter",
                destination = TutorialDestination.DASHBOARD,
                target = TutorialTarget.DASHBOARD_FILTER_CARD,
                anchor = SpotlightAnchor.STEP,
                iconRes = R.drawable.ic_filter,
                titleRes = R.string.tutorial_filter_title,
                bodyRes = R.string.tutorial_filter_body,
                placement = BalloonPlacement.PREFER_BELOW
            ),
            TutorialStep(
                id = "monitoring_tab",
                destination = TutorialDestination.DASHBOARD,
                target = TutorialTarget.TAB_MONITORING,
                anchor = SpotlightAnchor.STEP,
                iconRes = R.drawable.ic_wave,
                titleRes = R.string.tutorial_monitoring_title,
                bodyRes = R.string.tutorial_monitoring_body,
                placement = BalloonPlacement.PREFER_ABOVE
            ),
            TutorialStep(
                id = "monitoring_tank",
                destination = TutorialDestination.MONITORING,
                target = TutorialTarget.MONITORING_TANK_CARD,
                anchor = SpotlightAnchor.STEP,
                iconRes = R.drawable.ic_tank,
                titleRes = R.string.tutorial_tank_title,
                bodyRes = R.string.tutorial_tank_body,
                placement = BalloonPlacement.PREFER_BELOW
            ),
            TutorialStep(
                id = "monitoring_sensors",
                destination = TutorialDestination.MONITORING,
                target = TutorialTarget.MONITORING_PH_CARD,
                anchor = SpotlightAnchor.REGION,
                iconRes = R.drawable.ic_ph_tube,
                titleRes = R.string.tutorial_readings_title,
                bodyRes = R.string.tutorial_readings_body,
                placement = BalloonPlacement.PREFER_BELOW
            ),
            TutorialStep(
                id = "control_tab",
                destination = TutorialDestination.MONITORING,
                target = TutorialTarget.TAB_CONTROL,
                anchor = SpotlightAnchor.STEP,
                iconRes = R.drawable.ic_pump,
                titleRes = R.string.tutorial_control_title,
                bodyRes = R.string.tutorial_control_body,
                placement = BalloonPlacement.PREFER_ABOVE
            ),
            TutorialStep(
                id = "control_mode",
                destination = TutorialDestination.CONTROL,
                target = TutorialTarget.CONTROL_MODE_CARD,
                anchor = SpotlightAnchor.STEP,
                iconRes = R.drawable.ic_switch_account,
                titleRes = R.string.tutorial_mode_title,
                bodyRes = R.string.tutorial_mode_body,
                placement = BalloonPlacement.PREFER_BELOW
            ),
            TutorialStep(
                id = "control_pumps",
                destination = TutorialDestination.CONTROL,
                target = TutorialTarget.CONTROL_PUMP_A_CARD,
                anchor = SpotlightAnchor.REGION,
                iconRes = R.drawable.ic_pump,
                titleRes = R.string.tutorial_pumps_title,
                bodyRes = R.string.tutorial_pumps_body,
                placement = BalloonPlacement.PREFER_BELOW
            ),
            TutorialStep(
                id = "settings",
                destination = TutorialDestination.CONTROL,
                target = TutorialTarget.SETTINGS_BUTTON,
                anchor = SpotlightAnchor.STEP,
                iconRes = R.drawable.ic_gear,
                titleRes = R.string.tutorial_settings_title,
                bodyRes = R.string.tutorial_settings_body,
                placement = BalloonPlacement.PREFER_BELOW
            ),
            TutorialStep(
                id = "finish",
                destination = TutorialDestination.DASHBOARD,
                target = TutorialTarget.TUTORIAL_HEADER,
                anchor = SpotlightAnchor.NONE,
                iconRes = R.drawable.ic_water_drop_splash,
                titleRes = R.string.tutorial_finish_title,
                bodyRes = R.string.tutorial_finish_body
            )
        )
    }
}
