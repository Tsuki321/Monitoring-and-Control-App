package com.watermonitor.app.ui.monitoring

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.watermonitor.app.R
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

    // Track previous status label so we can flash only when the status actually changes.
    // 0 means "not yet seen" — the first emission shouldn't trigger a flash.
    private var prevPhStatusRes = 0
    private var prevTdsStatusRes = 0
    private var prevTurbidityStatusRes = 0

    // Track previous status color so a band change can tween from the old hue to the new one.
    private var prevPhColor = 0
    private var prevTdsColor = 0
    private var prevTurbidityColor = 0

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
                if (state.sensorData.ph != prevPh) AnimationUtils.pulseView(binding.imgPhIcon)
                prevPhColor = applyStatus(
                    binding.tvPhStatus,
                    state.phStatus.statusLabelRes,
                    state.phStatus.statusColorRes,
                    prevPhStatusRes,
                    prevPhColor
                )
                prevPhStatusRes = state.phStatus.statusLabelRes
                prevPh = state.sensorData.ph

                // TDS card
                AnimationUtils.animateTextCount(
                    binding.tvTdsValue,
                    from = prevTds,
                    to = state.sensorData.tds.toDouble(),
                    decimals = 0
                )
                if (state.sensorData.tds.toDouble() != prevTds) AnimationUtils.pulseView(binding.imgTdsIcon)
                prevTdsColor = applyStatus(
                    binding.tvTdsStatus,
                    state.tdsStatus.statusLabelRes,
                    state.tdsStatus.statusColorRes,
                    prevTdsStatusRes,
                    prevTdsColor
                )
                prevTdsStatusRes = state.tdsStatus.statusLabelRes
                prevTds = state.sensorData.tds.toDouble()

                // Turbidity card
                AnimationUtils.animateTextCount(
                    binding.tvTurbidityValue,
                    from = prevTurbidity,
                    to = state.sensorData.turbidity,
                    decimals = 1
                )
                if (state.sensorData.turbidity != prevTurbidity) AnimationUtils.pulseView(binding.imgTurbidityIcon)
                prevTurbidityColor = applyStatus(
                    binding.tvTurbidityStatus,
                    state.turbidityStatus.statusLabelRes,
                    state.turbidityStatus.statusColorRes,
                    prevTurbidityStatusRes,
                    prevTurbidityColor
                )
                prevTurbidityStatusRes = state.turbidityStatus.statusLabelRes
                prevTurbidity = state.sensorData.turbidity

                binding.tvTurbidityCloudiness.text =
                    getString(R.string.cloudiness_format, state.turbidityStatus.cloudinessPercent)
                binding.tvTurbidityCloudiness.setTextColor(state.turbidityStatus.statusColorRes)
            }
        }
    }

    /**
     * Sets the status text and color. When the reading crosses into a different status
     * band (e.g. Neutral → Alkaline), the color tweens from the previous hue and the
     * label gives a subtle pulse. Returns the color now applied, for next-frame tracking.
     */
    private fun applyStatus(
        textView: TextView,
        labelRes: Int,
        color: Int,
        prevLabelRes: Int,
        prevColor: Int
    ): Int {
        textView.text = getString(R.string.status_format, getString(labelRes))
        if (prevLabelRes != 0 && prevLabelRes != labelRes) {
            AnimationUtils.transitionTextColor(textView, prevColor, color)
            AnimationUtils.pulseView(textView, scalePeak = 1.08f)
        } else {
            textView.setTextColor(color)
        }
        return color
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        hasAnimatedEntrance = false
    }
}
