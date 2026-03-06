package com.watermonitor.app.ui.monitoring

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.watermonitor.app.databinding.FragmentMonitoringBinding
import com.watermonitor.app.utils.AnimationUtils
import kotlinx.coroutines.launch

class MonitoringFragment : Fragment() {

    private var _binding: FragmentMonitoringBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MonitoringViewModel by viewModels()

    // Track previous values for count-up animation
    private var prevPh = 7.0
    private var prevTds = 150.0
    private var prevTurbidity = 1.5
    private var hasAnimatedEntrance = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMonitoringBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                // Entrance animation runs once
                if (!hasAnimatedEntrance) {
                    hasAnimatedEntrance = true
                    AnimationUtils.animateCardEntrance(
                        listOf(binding.cardPh, binding.cardTds, binding.cardTurbidity),
                        delayMs = 120
                    )
                }

                // pH card
                AnimationUtils.animateTextCount(
                    binding.tvPhValue,
                    from = prevPh,
                    to = state.sensorData.ph,
                    decimals = 2
                )
                binding.tvPhStatus.apply {
                    text = "Status: ${state.phStatus.statusLabel}"
                    setTextColor(state.phStatus.statusColorRes)
                }
                prevPh = state.sensorData.ph

                // TDS card
                AnimationUtils.animateTextCount(
                    binding.tvTdsValue,
                    from = prevTds,
                    to = state.sensorData.tds.toDouble(),
                    decimals = 0
                )
                binding.tvTdsStatus.apply {
                    text = "Status: ${state.tdsStatus.statusLabel}"
                    setTextColor(state.tdsStatus.statusColorRes)
                }
                prevTds = state.sensorData.tds.toDouble()

                // Turbidity card
                AnimationUtils.animateTextCount(
                    binding.tvTurbidityValue,
                    from = prevTurbidity,
                    to = state.sensorData.turbidity,
                    decimals = 1
                )
                binding.tvTurbidityStatus.apply {
                    text = "Status: ${state.turbidityStatus.statusLabel}"
                    setTextColor(state.turbidityStatus.statusColorRes)
                }
                prevTurbidity = state.sensorData.turbidity
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        hasAnimatedEntrance = false
    }
}
