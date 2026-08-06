package com.watermonitor.app.ui.monitoring

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
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
    private var prevCloudiness = 0.0
    private var hasAnimatedEntrance = false

    /**
     * False until the first emission has been rendered. The count-ups below are gated on
     * value-changed (firmware V17 republishes /sensors every 5s, and re-running the animator
     * on an unchanged value makes the digits stutter), but a first reading that happens to
     * equal the seeded default would then never be written to the TextView at all.
     */
    private var hasRenderedValues = false

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
                        listOf(
                            binding.cardTank,
                            binding.cardPh,
                            binding.cardTds,
                            binding.cardTurbidity
                        ),
                        delayMs = 120
                    )
                }

                renderTankStatus(
                    state.sensorData.tankLevel,
                    state.sensorData.tankWarning,
                    state.sensorData.tankDistanceMm,
                    state.sensorData.leakDetected
                )

                // pH card. The count-up only runs on a genuine change: the ESP32 republishes
                // /sensors every 5s, and restarting the animator on an unchanged value makes
                // the digits visibly stutter.
                if (!hasRenderedValues || state.sensorData.ph != prevPh) {
                    AnimationUtils.animateTextCount(
                        binding.tvPhValue,
                        from = prevPh,
                        to = state.sensorData.ph,
                        decimals = 2
                    )
                    AnimationUtils.pulseView(binding.imgPhIcon)
                }
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
                if (!hasRenderedValues || state.sensorData.tds.toDouble() != prevTds) {
                    AnimationUtils.animateTextCount(
                        binding.tvTdsValue,
                        from = prevTds,
                        to = state.sensorData.tds.toDouble(),
                        decimals = 0
                    )
                    AnimationUtils.pulseView(binding.imgTdsIcon)
                }
                prevTdsColor = applyStatus(
                    binding.tvTdsStatus,
                    state.tdsStatus.statusLabelRes,
                    state.tdsStatus.statusColorRes,
                    prevTdsStatusRes,
                    prevTdsColor
                )
                prevTdsStatusRes = state.tdsStatus.statusLabelRes
                prevTds = state.sensorData.tds.toDouble()

                // Turbidity card — big value is cloudiness %, NTU reading moves to unit row
                if (!hasRenderedValues ||
                    state.turbidityStatus.cloudinessPercent.toDouble() != prevCloudiness
                ) {
                    AnimationUtils.animateTextCount(
                        binding.tvTurbidityValue,
                        from = prevCloudiness,
                        to = state.turbidityStatus.cloudinessPercent.toDouble(),
                        suffix = "%",
                        decimals = 0
                    )
                }
                binding.tvTurbidityUnit.text =
                    getString(R.string.ntu_value_format, state.sensorData.turbidity)
                val isCloudy = state.turbidityStatus.cloudinessPercent > CLOUDY_THRESHOLD_PERCENT
                binding.tvTurbidityCloudiness.text =
                    getString(if (isCloudy) R.string.status_cloudy else R.string.status_not_cloudy)
                binding.tvTurbidityCloudiness.setTextColor(state.turbidityStatus.statusColorRes)
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
                prevCloudiness = state.turbidityStatus.cloudinessPercent.toDouble()
                hasRenderedValues = true
            }
        }
    }

    private fun renderTankStatus(
        tankLevel: Float?,
        reportedWarning: Int?,
        tankDistanceMm: Int?,
        leakDetected: Boolean?
    ) {
        // Firmware sends -1 when the VL53L1X ToF sensor is offline.
        binding.tvTankDistance.text = when {
            tankDistanceMm == null -> getString(R.string.tank_distance_waiting)
            tankDistanceMm < 0 -> getString(R.string.tank_distance_offline)
            else -> getString(R.string.tank_distance_format, tankDistanceMm)
        }

        renderLeakStatus(leakDetected)

        if (tankLevel == null) {
            binding.waterTankView.setFillPercent(0f)
            binding.tvTankPercent.setText(R.string.tank_percent_waiting)
            binding.tvTankWarning.visibility = View.GONE
            return
        }

        val level = tankLevel.coerceIn(0f, 100f)
        val warning = reportedWarning ?: when {
            level >= 100f -> 3
            level >= 90f -> 2
            level >= 80f -> 1
            else -> 0
        }

        binding.waterTankView.setFillPercent(level)
        binding.tvTankPercent.text = getString(R.string.tank_percent_format, level.toInt())

        val (messageRes, colorRes) = when (warning) {
            1 -> R.string.tank_warning_80 to R.color.status_yellow
            2 -> R.string.tank_warning_90 to R.color.status_yellow
            3 -> R.string.tank_warning_100 to R.color.status_red
            else -> null to null
        }

        if (messageRes == null || colorRes == null) {
            binding.tvTankWarning.visibility = View.GONE
        } else {
            binding.tvTankWarning.apply {
                visibility = View.VISIBLE
                setText(messageRes)
                setTextColor(ContextCompat.getColor(requireContext(), colorRes))
            }
        }
    }

    private fun renderLeakStatus(leakDetected: Boolean?) {
        val (textRes, colorRes) = when (leakDetected) {
            true -> R.string.leak_status_detected to R.color.status_red
            false -> R.string.leak_status_dry to R.color.status_green
            null -> R.string.leak_status_waiting to R.color.text_secondary
        }
        binding.tvLeakStatus.apply {
            setText(textRes)
            setTextColor(ContextCompat.getColor(requireContext(), colorRes))
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
        hasRenderedValues = false
        // Reset prev* fields so count-up animations start from the current value
        // on view recreation instead of a stale baseline.
        prevPh = 7.0
        prevTds = 150.0
        prevTurbidity = 1.5
        prevCloudiness = 0.0
        prevPhStatusRes = 0
        prevTdsStatusRes = 0
        prevTurbidityStatusRes = 0
        prevPhColor = 0
        prevTdsColor = 0
        prevTurbidityColor = 0
    }

    private companion object {
        const val CLOUDY_THRESHOLD_PERCENT = 30
    }
}
