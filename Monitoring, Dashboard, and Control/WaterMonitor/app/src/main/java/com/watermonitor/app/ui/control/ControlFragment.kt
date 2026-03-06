package com.watermonitor.app.ui.control

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
        binding.switchValveMain.setOnCheckedChangeListener { _, _ ->
            if (!isUpdatingUi) {
                viewModel.toggleValveMain()
                AnimationUtils.pulseView(binding.cardValveMain)
            }
        }
        binding.switchValveBypass.setOnCheckedChangeListener { _, _ ->
            if (!isUpdatingUi) {
                viewModel.toggleValveBypass()
                AnimationUtils.pulseView(binding.cardValveBypass)
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pumpState.collect { state ->
                if (!hasAnimatedEntrance) {
                    hasAnimatedEntrance = true
                    AnimationUtils.animateCardEntrance(
                        listOf(
                            binding.cardPumpA,
                            binding.cardPumpB,
                            binding.cardValveMain,
                            binding.cardValveBypass,
                            binding.cardSystemStatus
                        ),
                        delayMs = 80
                    )
                }

                isUpdatingUi = true

                binding.switchPumpA.isChecked = state.pumpA
                binding.switchPumpB.isChecked = state.pumpB
                binding.switchValveMain.isChecked = state.valveMain
                binding.switchValveBypass.isChecked = state.valveBypass

                updateStateLabel(binding.tvPumpAState, state.pumpA)
                updateStateLabel(binding.tvPumpBState, state.pumpB)
                updateStateLabel(binding.tvValveMainState, state.valveMain)
                updateStateLabel(binding.tvValveBypassState, state.valveBypass)

                // System overall status
                val anyActive = state.pumpA || state.pumpB || state.valveMain || state.valveBypass
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
