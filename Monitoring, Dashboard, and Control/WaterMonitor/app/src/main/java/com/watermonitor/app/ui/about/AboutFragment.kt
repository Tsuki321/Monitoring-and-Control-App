package com.watermonitor.app.ui.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.watermonitor.app.databinding.FragmentAboutBinding
import com.watermonitor.app.ui.legal.LegalDialog

class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        binding.btnViewTerms.setOnClickListener {
            LegalDialog.showTerms(requireContext())
        }
        binding.btnViewPrivacy.setOnClickListener {
            LegalDialog.showPrivacyPolicy(requireContext())
        }
        binding.btnViewLicense.setOnClickListener {
            LegalDialog.showLicense(requireContext())
        }
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
