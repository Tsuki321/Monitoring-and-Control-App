package com.watermonitor.app.ui.dashboard

import android.animation.Animator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.watermonitor.app.R
import com.watermonitor.app.data.model.TankStatus
import com.watermonitor.app.databinding.FragmentDashboardBinding
import com.watermonitor.app.utils.AnimationUtils
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by viewModels()
    private var hasAnimatedEntrance = false

    // Breathing-pulse animators for the online sensor dots (null when offline)
    private var phDotPulse: Animator? = null
    private var tdsDotPulse: Animator? = null
    private var turbidityDotPulse: Animator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupInfoPopups()
        observeState()
    }

    private fun setupInfoPopups() {
        binding.cardTank.setOnClickListener {
            val state = viewModel.uiState.value.tankStatus
            val percent = state.fillPercent.toInt().coerceIn(0, 100)
            showInfoPopup(
                titleRes = R.string.tank_info_title,
                message = getString(
                    R.string.tank_info_message,
                    percent,
                    getString(tankStateLabel(state)),
                    onlineLabel(state.isOnline)
                )
            )
        }

        binding.cardSystemStatus.setOnClickListener {
            val state = viewModel.uiState.value.pumpState
            showInfoPopup(
                titleRes = R.string.pump_info_title,
                message = getString(
                    R.string.pump_info_message,
                    onOffLabel(state.pumpA),
                    onOffLabel(state.pumpB)
                )
            )
        }

        binding.cardSensorStatus.setOnClickListener {
            val state = viewModel.uiState.value.sensorStatus
            showInfoPopup(
                titleRes = R.string.sensor_info_title,
                message = getString(
                    R.string.sensor_info_message,
                    onlineLabel(state.phOnline),
                    onlineLabel(state.tdsOnline),
                    onlineLabel(state.turbidityOnline)
                )
            )
        }
    }

    private fun showInfoPopup(titleRes: Int, message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setIcon(R.drawable.ic_info)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun tankStateLabel(tankStatus: TankStatus): Int {
        val percent = tankStatus.fillPercent.coerceIn(0f, 100f)
        return when {
            tankStatus.tankWarning >= 3 || percent >= 100f -> R.string.tank_state_full
            tankStatus.tankWarning == 2 || percent >= 90f -> R.string.tank_state_critical
            tankStatus.tankWarning == 1 || percent >= 80f -> R.string.tank_state_near_capacity
            percent < 25f -> R.string.tank_state_low
            else -> R.string.tank_state_normal
        }
    }

    private fun onlineLabel(isOnline: Boolean): String = getString(
        if (isOnline) R.string.status_online else R.string.status_offline
    )

    private fun onOffLabel(isOn: Boolean): String = getString(
        if (isOn) R.string.state_on else R.string.state_off
    )

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                if (!hasAnimatedEntrance) {
                    hasAnimatedEntrance = true
                    AnimationUtils.animateCardEntrance(
                        listOf(
                            binding.cardTank,
                            binding.cardSystemStatus,
                            binding.cardSensorStatus
                        ),
                        delayMs = 100
                    )
                }

                // Tank
                binding.waterTankView.setFillPercent(state.tankStatus.fillPercent)
                binding.tvTankPercent.text = getString(R.string.tank_percent_format, state.tankStatus.fillPercent.toInt())

                // Tank warning banner (0=normal, 1=warning 80%, 2=critical 90%, 3=full 100%)
                val warning = state.tankStatus.tankWarning
                when (warning) {
                    0 -> {
                        binding.tvTankWarning.visibility = View.GONE
                    }
                    1 -> {
                        binding.tvTankWarning.apply {
                            visibility = View.VISIBLE
                            text = getString(R.string.tank_warning_80)
                            setTextColor(ContextCompat.getColor(requireContext(), R.color.status_yellow))
                        }
                    }
                    2 -> {
                        binding.tvTankWarning.apply {
                            visibility = View.VISIBLE
                            text = getString(R.string.tank_warning_90)
                            setTextColor(ContextCompat.getColor(requireContext(), R.color.status_yellow))
                        }
                    }
                    3 -> {
                        binding.tvTankWarning.apply {
                            visibility = View.VISIBLE
                            text = getString(R.string.tank_warning_100)
                            setTextColor(ContextCompat.getColor(requireContext(), R.color.status_red))
                        }
                    }
                }

                // Pump status
                val greenColor = ContextCompat.getColor(requireContext(), R.color.status_green)
                val greyColor = ContextCompat.getColor(requireContext(), R.color.status_grey)

                binding.tvPumpAStatus.apply {
                    val stateLabel = getString(if (state.pumpState.pumpA) R.string.state_on else R.string.state_off)
                    text = getString(R.string.sensor_status_format, getString(R.string.pump_a_label), stateLabel)
                    setTextColor(if (state.pumpState.pumpA) greenColor else greyColor)
                }
                binding.tvPumpBStatus.apply {
                    val stateLabel = getString(if (state.pumpState.pumpB) R.string.state_on else R.string.state_off)
                    text = getString(R.string.sensor_status_format, getString(R.string.pump_b_label), stateLabel)
                    setTextColor(if (state.pumpState.pumpB) greenColor else greyColor)
                }

                // Sensor online dots
                binding.dotPh.setColorFilter(if (state.sensorStatus.phOnline) greenColor else greyColor)
                binding.dotTds.setColorFilter(if (state.sensorStatus.tdsOnline) greenColor else greyColor)
                binding.dotTurbidity.setColorFilter(if (state.sensorStatus.turbidityOnline) greenColor else greyColor)

                // Breathing pulse on dots that are online; halt and reset when offline
                phDotPulse = updateDotPulse(state.sensorStatus.phOnline, binding.dotPh, phDotPulse)
                tdsDotPulse = updateDotPulse(state.sensorStatus.tdsOnline, binding.dotTds, tdsDotPulse)
                turbidityDotPulse = updateDotPulse(state.sensorStatus.turbidityOnline, binding.dotTurbidity, turbidityDotPulse)

                binding.tvPhOnline.text = sensorOnlineLabel(R.string.sensor_ph_short, state.sensorStatus.phOnline)
                binding.tvTdsOnline.text = sensorOnlineLabel(R.string.sensor_tds_short, state.sensorStatus.tdsOnline)
                binding.tvTurbidityOnline.text = sensorOnlineLabel(R.string.sensor_turbidity_short, state.sensorStatus.turbidityOnline)
            }
        }
    }

    /**
     * Keeps a sensor dot's breathing pulse in sync with its online state.
     * Returns the active animator (or null) so the caller can track it.
     */
    private fun updateDotPulse(isOnline: Boolean, dot: View, current: Animator?): Animator? {
        return if (isOnline) {
            current ?: AnimationUtils.startBreathingPulse(dot)
        } else {
            AnimationUtils.stopBreathingPulse(current, dot)
            null
        }
    }

    private fun sensorOnlineLabel(sensorNameRes: Int, isOnline: Boolean): String {
        val statusRes = if (isOnline) R.string.status_online else R.string.status_offline
        return getString(R.string.sensor_status_format, getString(sensorNameRes), getString(statusRes))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        phDotPulse?.cancel()
        tdsDotPulse?.cancel()
        turbidityDotPulse?.cancel()
        phDotPulse = null
        tdsDotPulse = null
        turbidityDotPulse = null
        _binding = null
        hasAnimatedEntrance = false
    }
}
