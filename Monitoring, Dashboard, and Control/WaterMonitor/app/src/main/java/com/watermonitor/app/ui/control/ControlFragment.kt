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
        binding.switchMode.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleAutoMode()
            AnimationUtils.pulseView(binding.cardMode)
        }
        binding.switchPumpA.setOnCheckedChangeListener { _, isChecked ->
            viewModel.togglePumpA()
            AnimationUtils.pulseView(binding.cardPumpA)
        }
        binding.switchPumpB.setOnCheckedChangeListener { _, isChecked ->
            viewModel.togglePumpB()
            AnimationUtils.pulseView(binding.cardPumpB)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pumpControlState.collect { control ->
                // Remove listeners before programmatic isChecked updates to avoid
                // triggering toggle commands, then re-attach after.
                binding.switchMode.setOnCheckedChangeListener(null)
                binding.switchPumpA.setOnCheckedChangeListener(null)
                binding.switchPumpB.setOnCheckedChangeListener(null)

                // Mode switch reflects autoMode from RTDB
                binding.switchMode.isChecked = control.autoMode
                updateModeLabel(control.autoMode)

                // Pump switches reflect the commanded state from RTDB /control so
                // they stay in sync with the user's intent even when no ESP32 is
                // online to acknowledge. Status labels below show the actual relay
                // state from /status.
                binding.switchPumpA.isChecked = control.commandedPumpA
                binding.switchPumpB.isChecked = control.commandedPumpB

                // Disable pump switches in AUTO mode or when a leak is active
                // (ESP32 force-stops pumps on leak regardless of app commands).
                val leakActive = viewModel.leakDetected.value == true
                val pumpsEnabled = !control.autoMode && !leakActive
                binding.switchPumpA.isEnabled = pumpsEnabled
                binding.switchPumpB.isEnabled = pumpsEnabled

                // Re-attach listeners
                binding.switchMode.setOnCheckedChangeListener { _, _ ->
                    viewModel.toggleAutoMode()
                    AnimationUtils.pulseView(binding.cardMode)
                }
                binding.switchPumpA.setOnCheckedChangeListener { _, _ ->
                    viewModel.togglePumpA()
                    AnimationUtils.pulseView(binding.cardPumpA)
                }
                binding.switchPumpB.setOnCheckedChangeListener { _, _ ->
                    viewModel.togglePumpB()
                    AnimationUtils.pulseView(binding.cardPumpB)
                }

                updateStateLabel(binding.tvPumpAState, control.actualPumpA)
                updateStateLabel(binding.tvPumpBState, control.actualPumpB)

                // Rotate pump icons while running; ease back to rest when stopped
                updatePumpRotation(control.actualPumpA, binding.imgPumpAIcon, isPumpA = true)
                updatePumpRotation(control.actualPumpB, binding.imgPumpBIcon, isPumpA = false)

                // System overall status reflects actual relay states
                val anyActive = control.actualPumpA || control.actualPumpB
                binding.tvSystemStatus.apply {
                    text = if (anyActive) getString(R.string.system_active) else getString(R.string.system_standby)
                    setTextColor(
                        if (anyActive) ContextCompat.getColor(requireContext(), R.color.status_green)
                        else ContextCompat.getColor(requireContext(), R.color.status_grey)
                    )
                }
            }
        }

        // Leak banner + pump-switch enablement when moisture sensor trips.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.leakDetected.collect { leak ->
                binding.cardLeakBanner.visibility =
                    if (leak == true) View.VISIBLE else View.GONE

                // Re-apply pump enable state when leak flips while on Control screen.
                val auto = viewModel.pumpControlState.value.autoMode
                val pumpsEnabled = !auto && leak != true
                binding.switchPumpA.isEnabled = pumpsEnabled
                binding.switchPumpB.isEnabled = pumpsEnabled
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
