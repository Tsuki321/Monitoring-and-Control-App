package com.watermonitor.app.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.watermonitor.app.R
import com.watermonitor.app.databinding.FragmentDashboardBinding
import com.watermonitor.app.utils.AnimationUtils
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by viewModels()
    private var hasAnimatedEntrance = false

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

        val bottomNav = requireActivity().findViewById<BottomNavigationView>(R.id.bottomNav)
        binding.btnGoMonitoring.setOnClickListener {
            bottomNav.selectedItemId = R.id.monitoringFragment
        }
        binding.btnGoControl.setOnClickListener {
            bottomNav.selectedItemId = R.id.controlFragment
        }

        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                if (!hasAnimatedEntrance) {
                    hasAnimatedEntrance = true
                    AnimationUtils.animateCardEntrance(
                        listOf(
                            binding.cardTank,
                            binding.cardSystemStatus,
                            binding.cardSensorStatus,
                            binding.btnGoMonitoring,
                            binding.btnGoControl
                        ),
                        delayMs = 100
                    )
                }

                // Tank
                binding.waterTankView.setFillPercent(state.tankStatus.fillPercent)
                binding.tvTankPercent.text = getString(R.string.tank_percent_format, state.tankStatus.fillPercent.toInt())

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

                binding.tvPhOnline.text = sensorOnlineLabel(R.string.sensor_ph_short, state.sensorStatus.phOnline)
                binding.tvTdsOnline.text = sensorOnlineLabel(R.string.sensor_tds_short, state.sensorStatus.tdsOnline)
                binding.tvTurbidityOnline.text = sensorOnlineLabel(R.string.sensor_turbidity_short, state.sensorStatus.turbidityOnline)
            }
        }
    }

    private fun sensorOnlineLabel(sensorNameRes: Int, isOnline: Boolean): String {
        val statusRes = if (isOnline) R.string.status_online else R.string.status_offline
        return getString(R.string.sensor_status_format, getString(sensorNameRes), getString(statusRes))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        hasAnimatedEntrance = false
    }
}
