package com.watermonitor.app.ui.filter

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.watermonitor.app.R
import com.watermonitor.app.data.model.FilterCondition
import com.watermonitor.app.data.model.FilterHealthState
import com.watermonitor.app.data.model.FilterSpecs
import com.watermonitor.app.data.model.FilterStageHealth
import com.watermonitor.app.data.model.LimitedBy
import com.watermonitor.app.data.model.RuntimeSource
import com.watermonitor.app.data.model.ServiceAction
import com.watermonitor.app.data.model.StageModelDiagnostics
import com.watermonitor.app.databinding.FragmentFilterBinding
import com.watermonitor.app.databinding.ItemFilterStageBinding
import com.watermonitor.app.utils.AnimationUtils
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Per-stage filter health, forecasts, service actions and model diagnostics.
 *
 * The five stage rows are inflated once into `stageContainer` and then rebound on each
 * emission, rather than being cleared and re-inflated — the screen updates every few seconds
 * once the ESP32 is publishing, and re-inflating would drop the ripple state of a button the
 * user is mid-press on.
 */
class FilterFragment : Fragment() {

    private var _binding: FragmentFilterBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FilterViewModel by viewModels()

    private val stageRows = mutableListOf<ItemFilterStageBinding>()
    private var hasAnimatedEntrance = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildStageRows()

        binding.btnResetHistory.setOnClickListener {
            viewModel.resetHistory()
            Toast.makeText(
                requireContext(),
                R.string.filter_model_reset_confirm,
                Toast.LENGTH_SHORT
            ).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.filterHealth.collect { render(it) }
        }
    }

    /** Inflates one row per spec stage and wires its service buttons by index. */
    private fun buildStageRows() {
        val inflater = LayoutInflater.from(requireContext())
        FilterSpecs.stages.forEach { spec ->
            val row = ItemFilterStageBinding.inflate(inflater, binding.stageContainer, false)

            // Rinse is only meaningful for reusable media; consumables are replace-only.
            row.btnRinse.visibility =
                if (spec.action == ServiceAction.RINSE) View.VISIBLE else View.GONE

            row.btnRinse.setOnClickListener {
                viewModel.rinseStage(spec.index)
                AnimationUtils.pulseView(row.progressStage, scalePeak = 1.04f)
            }
            row.btnReplace.setOnClickListener {
                viewModel.replaceStage(spec.index)
                AnimationUtils.pulseView(row.progressStage, scalePeak = 1.04f)
            }

            binding.stageContainer.addView(row.root)
            stageRows.add(row)
        }
    }

    private fun render(state: FilterHealthState) {
        if (!hasAnimatedEntrance) {
            hasAnimatedEntrance = true
            AnimationUtils.animateCardEntrance(
                listOf(binding.cardOverall, binding.cardModel),
                delayMs = 100
            )
        }

        renderOverall(state)
        state.stages.forEachIndexed { index, stage ->
            stageRows.getOrNull(index)?.let { bindStage(it, stage) }
        }
        renderDiagnostics(state.diagnostics)
    }

    private fun renderOverall(state: FilterHealthState) {
        val worst = state.worstStage
        if (state.isWaitingForData || worst == null) {
            binding.tvOverallCondition.setText(R.string.filter_waiting_for_data)
            binding.tvOverallCondition.setTextColor(color(R.color.status_grey))
            binding.tvOverallSummary.setText(R.string.filter_waiting_summary)
        } else {
            binding.tvOverallCondition.setText(FilterFormatter.conditionRes(worst.condition))
            binding.tvOverallCondition.setTextColor(
                color(FilterFormatter.conditionColorRes(worst.condition))
            )
            binding.tvOverallSummary.text = if (worst.condition == FilterCondition.GOOD) {
                getString(R.string.filter_summary_all_good)
            } else {
                getString(
                    R.string.filter_summary_worst_format,
                    FilterFormatter.stageName(requireContext(), worst.stageKey),
                    FilterFormatter.forecastLabel(requireContext(), worst)
                )
            }
        }

        binding.tvRuntimeSource.setText(
            when (state.runtimeSource) {
                RuntimeSource.HARDWARE -> R.string.filter_source_hardware
                RuntimeSource.SIMULATED -> R.string.filter_source_simulated
            }
        )
        binding.tvRuntimeSource.setTextColor(
            color(
                if (state.runtimeSource == RuntimeSource.HARDWARE) {
                    R.color.text_dark
                } else {
                    R.color.status_yellow
                }
            )
        )

        val total = getString(R.string.filter_runtime_total_format, state.totalRuntimeHours)
        val duty = state.dutyCycle
        binding.tvRuntimeTotal.text = if (duty != null && duty.isFinite()) {
            "$total · " + getString(
                R.string.filter_duty_cycle_format,
                (duty * 100).roundToInt().coerceIn(0, 9999)
            )
        } else {
            total
        }
    }

    private fun bindStage(row: ItemFilterStageBinding, stage: FilterStageHealth) {
        val conditionColor = color(FilterFormatter.conditionColorRes(stage.condition))

        row.tvStageName.text = FilterFormatter.stageName(requireContext(), stage.stageKey)
        row.tvStageCondition.setText(FilterFormatter.conditionRes(stage.condition))
        row.tvStageCondition.setTextColor(conditionColor)

        row.progressStage.setIndicatorColor(conditionColor)
        row.progressStage.setProgressCompat(FilterFormatter.healthPercent(stage), true)

        row.tvStageHealth.text = FilterFormatter.healthLabel(requireContext(), stage)
        row.tvStageLimitedBy.setText(
            when (stage.limitedBy) {
                LimitedBy.RUNTIME -> R.string.filter_limited_by_runtime
                LimitedBy.CALENDAR -> R.string.filter_limited_by_calendar
            }
        )
        row.tvStageForecast.text = FilterFormatter.forecastLabel(requireContext(), stage)

        val serviced = stage.daysSinceService.roundToInt()
        val servicedLabel = if (serviced <= 0) {
            getString(R.string.filter_serviced_today)
        } else {
            getString(R.string.filter_serviced_days_ago_format, serviced)
        }
        row.tvStageDetail.text = servicedLabel + " · " +
            getString(R.string.filter_load_factor_format, stage.loadFactor)

        // A rinse stage that is calendar-bound cannot be helped by rinsing; say so rather
        // than letting the user rinse repeatedly and conclude the feature is broken.
        row.tvStageRinseWarning.visibility =
            if (stage.rinseWontHelp) View.VISIBLE else View.GONE
        row.btnRinse.isEnabled = !stage.rinseWontHelp
    }

    private fun renderDiagnostics(diagnostics: List<StageModelDiagnostics>) {
        val container = binding.diagnosticsContainer
        // Rebuild only when the row count changes; otherwise rebind in place.
        if (container.childCount != diagnostics.size) {
            container.removeAllViews()
            repeat(diagnostics.size) {
                container.addView(
                    TextView(requireContext()).apply {
                        textSize = 12f
                        setTextColor(color(R.color.text_secondary))
                        setPadding(0, 0, 0, 6)
                    }
                )
            }
        }

        diagnostics.forEachIndexed { index, item ->
            val view = container.getChildAt(index) as? TextView ?: return@forEachIndexed
            view.text = getString(
                R.string.filter_model_row_format,
                FilterFormatter.stageName(requireContext(), item.stageKey),
                (item.rSquared * 100).roundToInt().coerceIn(0, 100),
                item.realObservations
            )
        }
    }

    private fun color(resId: Int) = ContextCompat.getColor(requireContext(), resId)

    override fun onDestroyView() {
        super.onDestroyView()
        // Rows hold references to the destroyed view hierarchy; clearing prevents a leak.
        stageRows.clear()
        hasAnimatedEntrance = false
        _binding = null
    }
}
