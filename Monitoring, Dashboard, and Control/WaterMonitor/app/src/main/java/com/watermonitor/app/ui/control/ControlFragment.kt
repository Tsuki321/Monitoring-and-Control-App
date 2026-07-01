package com.watermonitor.app.ui.control

import android.animation.Animator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.watermonitor.app.R
import com.watermonitor.app.databinding.FragmentControlBinding
import com.watermonitor.app.utils.AnimationUtils
import kotlinx.coroutines.launch

class ControlFragment : Fragment() {

    private var _binding: FragmentControlBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ControlViewModel by viewModels()
    private var hasAnimatedEntrance = false

    // Prevent switch listener from triggering during state update
    private var isUpdatingUi = false

    // Active-pump icon rotation animators (null when the pump is off)
    private var pumpARotation: Animator? = null
    private var pumpBRotation: Animator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentControlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSwitchListeners()
        observeState()
    }

    private fun setupSwitchListeners() {
        binding.switchMode.setOnCheckedChangeListener { _, _ ->
            if (!isUpdatingUi) {
                viewModel.toggleAutoMode()
                AnimationUtils.pulseView(binding.cardMode)
            }
        }
        binding.switchPumpA.setOnCheckedChangeListener { _, _ ->
            if (!isUpdatingUi) {
                viewModel.togglePumpA()
                AnimationUtils.pulseView(binding.cardPumpA)
            }
        }
        binding.switchPumpB.setOnCheckedChangeListener { _, _ ->
            if (!isUpdatingUi) {
                viewModel.togglePumpB()
                AnimationUtils.pulseView(binding.cardPumpB)
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pumpControlState.collect { control ->
                isUpdatingUi = true

                // Mode switch reflects autoMode from RTDB
                binding.switchMode.isChecked = control.autoMode
                updateModeLabel(control.autoMode)

                // Pump switches reflect actual relay states from RTDB /status
                binding.switchPumpA.isChecked = control.actualPumpA
                binding.switchPumpB.isChecked = control.actualPumpB

                // Disable pump switches in AUTO mode (ESP32 controls them)
                binding.switchPumpA.isEnabled = !control.autoMode
                binding.switchPumpB.isEnabled = !control.autoMode

                updateStateLabel(binding.tvPumpAState, control.actualPumpA)
                updateStateLabel(binding.tvPumpBState, control.actualPumpB)

                // Rotate pump icons while running; ease back to rest when stopped
                updatePumpRotation(control.actualPumpA, binding.imgPumpAIcon, isPumpA = true)
                updatePumpRotation(control.actualPumpB, binding.imgPumpBIcon, isPumpA = false)

                // System overall status
                val anyActive = control.actualPumpA || control.actualPumpB
                binding.tvSystemStatus.apply {
                    text = if (anyActive) getString(R.string.system_active) else getString(R.string.system_standby)
                    setTextColor(
                        if (anyActive) ContextCompat.getColor(requireContext(), R.color.status_green)
                        else ContextCompat.getColor(requireContext(), R.color.status_grey)
                    )
                }

                isUpdatingUi = false
            }
        }

        // Speed / voltage from the mock simulation (synced to actual states)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pumpState.collect { state ->
                if (!hasAnimatedEntrance) {
                    hasAnimatedEntrance = true
                    AnimationUtils.animateCardEntrance(
                        listOf(
                            binding.cardMode,
                            binding.cardPumpA,
                            binding.cardPumpB,
                            binding.cardSystemStatus
                        ),
                        delayMs = 80
                    )
                }

                binding.tvPumpASpeed.text = getString(R.string.pump_speed_format, state.pumpASpeed)
                binding.tvPumpAVoltage.text = getString(R.string.pump_voltage_format, state.pumpAVoltage)
                binding.tvPumpBSpeed.text = getString(R.string.pump_speed_format, state.pumpBSpeed)
                binding.tvPumpBVoltage.text = getString(R.string.pump_voltage_format, state.pumpBVoltage)
            }
        }
    }

    private fun updateModeLabel(isAuto: Boolean) {
        binding.tvModeState.text = if (isAuto) getString(R.string.mode_auto) else getString(R.string.mode_manual)
        binding.tvModeState.setTextColor(
            if (isAuto) ContextCompat.getColor(requireContext(), R.color.status_green)
            else ContextCompat.getColor(requireContext(), R.color.status_grey)
        )
        binding.tvModeHint.text = if (isAuto) getString(R.string.pump_auto_hint) else getString(R.string.pump_manual_hint)
    }

    private fun updateStateLabel(textView: android.widget.TextView, isOn: Boolean) {
        textView.text = if (isOn) getString(R.string.state_on) else getString(R.string.state_off)
        textView.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (isOn) R.color.status_green else R.color.status_grey
            )
        )
    }

    /**
     * Starts a continuous rotation on a pump icon when it turns on and stops it when off.
     * Guards against restarting an already-running animator each time state re-emits.
     */
    private fun updatePumpRotation(isOn: Boolean, icon: View, isPumpA: Boolean) {
        val current = if (isPumpA) pumpARotation else pumpBRotation
        if (isOn) {
            if (current == null) {
                val animator = AnimationUtils.startRotation(icon)
                if (isPumpA) pumpARotation = animator else pumpBRotation = animator
            }
        } else if (current != null) {
            AnimationUtils.stopRotation(current, icon)
            if (isPumpA) pumpARotation = null else pumpBRotation = null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pumpARotation?.cancel()
        pumpBRotation?.cancel()
        pumpARotation = null
        pumpBRotation = null
        _binding = null
        hasAnimatedEntrance = false
    }
}
