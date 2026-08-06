package com.watermonitor.app.ui.dashboard

import android.animation.Animator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.watermonitor.app.R
import com.watermonitor.app.data.model.FilterCondition
import com.watermonitor.app.data.model.FilterHealthState
import com.watermonitor.app.data.model.FilterSpecs
import com.watermonitor.app.data.model.RuntimeSource
import com.watermonitor.app.data.model.WaterSafetyLevel
import com.watermonitor.app.data.repository.FilterHealthRepository
import com.watermonitor.app.databinding.FragmentDashboardBinding
import com.watermonitor.app.ui.filter.FilterFormatter
import com.watermonitor.app.utils.AnimationUtils
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by viewModels()
    private var hasAnimatedEntrance = false
    private var previousWaterSafety: WaterSafetyLevel? = null
    private var previousFilterCondition: FilterCondition? = null

    private val filterSummaryRows = mutableListOf<View>()

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
        buildFilterSummaryRows()
        binding.cardFilterHealth.setOnClickListener {
            findNavController().navigate(R.id.action_dashboardFragment_to_filterFragment)
        }
        observeState()
        observeFilterHealth()
    }

    /**
     * Collected separately rather than folded into [DashboardViewModel]'s `combine`.
     *
     * `combine` emits nothing until *every* source has emitted at least once, so wiring
     * filter health into it would freeze the whole dashboard at its initial value whenever
     * the filter repository was slow to publish — for instance while it loads prefs and fits
     * five models off the main thread on a cold start.
     */
    private fun observeFilterHealth() {
        viewLifecycleOwner.lifecycleScope.launch {
            FilterHealthRepository.filterHealthFlow.collect { renderFilterHealth(it) }
        }
    }

    /** Inflates one compact row per stage; rebound in place on each emission. */
    private fun buildFilterSummaryRows() {
        val inflater = LayoutInflater.from(requireContext())
        FilterSpecs.stages.forEach { _ ->
            val row = inflater.inflate(
                R.layout.item_filter_summary_row,
                binding.filterStageSummary,
                false
            )
            binding.filterStageSummary.addView(row)
            filterSummaryRows.add(row)
        }
    }

    private fun renderFilterHealth(state: FilterHealthState) {
        val worst = state.worstStage
        if (state.isWaitingForData || worst == null) {
            binding.tvFilterCondition.setText(R.string.filter_waiting_for_data)
            binding.tvFilterCondition.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.status_grey)
            )
            binding.tvFilterSummary.setText(R.string.filter_waiting_summary)
            binding.imgFilterStatus.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.status_grey)
            )
        } else {
            val statusColor = ContextCompat.getColor(
                requireContext(),
                FilterFormatter.conditionColorRes(worst.condition)
            )
            binding.tvFilterCondition.setText(FilterFormatter.conditionRes(worst.condition))
            binding.tvFilterCondition.setTextColor(statusColor)
            binding.imgFilterStatus.setColorFilter(statusColor)
            binding.tvFilterSummary.text = if (worst.condition == FilterCondition.GOOD) {
                getString(R.string.filter_summary_all_good)
            } else {
                getString(
                    R.string.filter_summary_worst_format,
                    FilterFormatter.stageName(requireContext(), worst.stageKey),
                    FilterFormatter.forecastLabel(requireContext(), worst)
                )
            }

            if (previousFilterCondition != null && previousFilterCondition != worst.condition) {
                AnimationUtils.pulseView(binding.tvFilterCondition, scalePeak = 1.06f)
                AnimationUtils.pulseView(binding.imgFilterStatus, scalePeak = 1.15f)
            }
            previousFilterCondition = worst.condition
        }

        // Rows carry no meaning until the repository has published; showing five empty bars
        // reads as "all stages at zero" rather than "not loaded yet".
        binding.filterStageSummary.visibility =
            if (state.stages.isEmpty()) View.GONE else View.VISIBLE

        state.stages.forEachIndexed { index, stage ->
            val row = filterSummaryRows.getOrNull(index) ?: return@forEachIndexed
            val name = row.findViewById<TextView>(R.id.tvSummaryStageName)
            val bar = row.findViewById<LinearProgressIndicator>(R.id.progressSummaryStage)
            val percent = row.findViewById<TextView>(R.id.tvSummaryStagePercent)
            val color = ContextCompat.getColor(
                requireContext(),
                FilterFormatter.conditionColorRes(stage.condition)
            )
            val value = FilterFormatter.healthPercent(stage)

            name.text = FilterFormatter.stageName(requireContext(), stage.stageKey)
            bar.setIndicatorColor(color)
            bar.setProgressCompat(value, true)
            percent.text = getString(R.string.filter_percent_format, value)
            percent.setTextColor(color)
        }

        binding.tvFilterSource.setText(
            when (state.runtimeSource) {
                RuntimeSource.HARDWARE -> R.string.filter_source_hardware
                RuntimeSource.SIMULATED -> R.string.filter_source_simulated
            }
        )
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                if (!hasAnimatedEntrance) {
                    hasAnimatedEntrance = true
                    AnimationUtils.animateCardEntrance(
                        listOf(
                            binding.cardWaterSafety,
                            binding.cardSystemStatus,
                            binding.cardSensorStatus,
                            binding.cardFilterHealth
                        ),
                        delayMs = 100
                    )
                }

                renderWaterSafety(state)
                renderSystemStatus(state)
                renderSensorConnections(state)
            }
        }
    }

    private fun renderWaterSafety(state: DashboardUiState) {
        val safety = if (state.hasSensorReading) {
            state.waterQuality.overallSafety
        } else {
            WaterSafetyLevel.UNKNOWN
        }
        val (statusRes, summaryRes, colorRes) = when (safety) {
            WaterSafetyLevel.UNKNOWN -> Triple(
                R.string.water_safety_checking,
                R.string.water_safety_checking_summary,
                R.color.status_grey
            )
            WaterSafetyLevel.SAFE -> Triple(
                R.string.water_safety_safe,
                R.string.water_safety_safe_summary,
                R.color.status_green
            )
            WaterSafetyLevel.CAUTION -> Triple(
                R.string.water_safety_caution,
                R.string.water_safety_caution_summary,
                R.color.status_yellow
            )
            WaterSafetyLevel.UNSAFE -> Triple(
                R.string.water_safety_unsafe,
                R.string.water_safety_unsafe_summary,
                R.color.status_red
            )
        }
        val statusColor = ContextCompat.getColor(requireContext(), colorRes)

        binding.tvWaterSafetyStatus.apply {
            setText(statusRes)
            setTextColor(statusColor)
        }
        binding.tvWaterSafetySummary.setText(summaryRes)
        binding.imgWaterSafetyStatus.setColorFilter(statusColor)
        if (state.hasSensorReading) {
            binding.tvSafetyPh.text = getString(R.string.water_safety_ph_format, state.sensorData.ph)
            binding.tvSafetyTds.text = getString(R.string.water_safety_tds_format, state.sensorData.tds)
            binding.tvSafetyTurbidity.text = getString(
                R.string.water_safety_turbidity_format,
                state.sensorData.turbidity
            )
        } else {
            binding.tvSafetyPh.setText(R.string.water_safety_ph_pending)
            binding.tvSafetyTds.setText(R.string.water_safety_tds_pending)
            binding.tvSafetyTurbidity.setText(R.string.water_safety_turbidity_pending)
        }

        if (previousWaterSafety != null && previousWaterSafety != safety) {
            AnimationUtils.pulseView(binding.tvWaterSafetyStatus, scalePeak = 1.06f)
            AnimationUtils.pulseView(binding.imgWaterSafetyStatus, scalePeak = 1.15f)
        }
        previousWaterSafety = safety
    }

    private fun renderSystemStatus(state: DashboardUiState) {
        val greenColor = ContextCompat.getColor(requireContext(), R.color.status_green)
        val greyColor = ContextCompat.getColor(requireContext(), R.color.status_grey)
        val redColor = ContextCompat.getColor(requireContext(), R.color.status_red)

        // Leak sensor — RTDB key rainDetected, displayed as leak
        when (state.sensorData.leakDetected) {
            true -> {
                binding.tvLeakStatus.setText(R.string.leak_status_detected)
                binding.tvLeakStatus.setTextColor(redColor)
                binding.dotLeak.setColorFilter(redColor)
            }
            false -> {
                binding.tvLeakStatus.setText(R.string.leak_status_dry)
                binding.tvLeakStatus.setTextColor(greenColor)
                binding.dotLeak.setColorFilter(greenColor)
            }
            null -> {
                binding.tvLeakStatus.setText(R.string.leak_status_waiting)
                binding.tvLeakStatus.setTextColor(greyColor)
                binding.dotLeak.setColorFilter(greyColor)
            }
        }

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
    }

    private fun renderSensorConnections(state: DashboardUiState) {
        val greenColor = ContextCompat.getColor(requireContext(), R.color.status_green)
        val greyColor = ContextCompat.getColor(requireContext(), R.color.status_grey)

        binding.dotPh.setColorFilter(if (state.sensorStatus.phOnline) greenColor else greyColor)
        binding.dotTds.setColorFilter(if (state.sensorStatus.tdsOnline) greenColor else greyColor)
        binding.dotTurbidity.setColorFilter(if (state.sensorStatus.turbidityOnline) greenColor else greyColor)

        phDotPulse = updateDotPulse(state.sensorStatus.phOnline, binding.dotPh, phDotPulse)
        tdsDotPulse = updateDotPulse(state.sensorStatus.tdsOnline, binding.dotTds, tdsDotPulse)
        turbidityDotPulse = updateDotPulse(
            state.sensorStatus.turbidityOnline,
            binding.dotTurbidity,
            turbidityDotPulse
        )

        binding.tvPhOnline.text = sensorOnlineLabel(R.string.sensor_ph_short, state.sensorStatus.phOnline)
        binding.tvTdsOnline.text = sensorOnlineLabel(R.string.sensor_tds_short, state.sensorStatus.tdsOnline)
        binding.tvTurbidityOnline.text = sensorOnlineLabel(
            R.string.sensor_turbidity_short,
            state.sensorStatus.turbidityOnline
        )
    }

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
        previousWaterSafety = null
        previousFilterCondition = null
        filterSummaryRows.clear()
        _binding = null
        hasAnimatedEntrance = false
    }
}
