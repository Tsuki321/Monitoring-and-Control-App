package com.watermonitor.app.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
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

        binding.btnGoMonitoring.setOnClickListener {
            findNavController().navigate(R.id.action_dashboardFragment_to_monitoringFragment)
        }
        binding.btnGoControl.setOnClickListener {
            findNavController().navigate(R.id.action_dashboardFragment_to_controlFragment)
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
                binding.tvTankPercent.text = "Tank: ${state.tankStatus.fillPercent.toInt()}%"

                // Pump status
                val greenColor = ContextCompat.getColor(requireContext(), R.color.status_green)
                val greyColor = ContextCompat.getColor(requireContext(), R.color.status_grey)

                binding.tvPumpAStatus.apply {
                    text = "Pump A: ${if (state.pumpState.pumpA) "ON" else "OFF"}"
                    setTextColor(if (state.pumpState.pumpA) greenColor else greyColor)
                }
                binding.tvPumpBStatus.apply {
                    text = "Pump B: ${if (state.pumpState.pumpB) "ON" else "OFF"}"
                    setTextColor(if (state.pumpState.pumpB) greenColor else greyColor)
                }

                // Sensor online dots
                binding.dotPh.setColorFilter(if (state.sensorStatus.phOnline) greenColor else greyColor)
                binding.dotTds.setColorFilter(if (state.sensorStatus.tdsOnline) greenColor else greyColor)
                binding.dotTurbidity.setColorFilter(if (state.sensorStatus.turbidityOnline) greenColor else greyColor)

                binding.tvPhOnline.text = "pH: ${if (state.sensorStatus.phOnline) "Online" else "Offline"}"
                binding.tvTdsOnline.text = "TDS: ${if (state.sensorStatus.tdsOnline) "Online" else "Offline"}"
                binding.tvTurbidityOnline.text = "Turbidity: ${if (state.sensorStatus.turbidityOnline) "Online" else "Offline"}"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        hasAnimatedEntrance = false
    }
}
