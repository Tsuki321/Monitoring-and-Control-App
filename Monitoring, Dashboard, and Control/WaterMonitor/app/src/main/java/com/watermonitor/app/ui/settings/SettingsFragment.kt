package com.watermonitor.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.watermonitor.app.R
import com.watermonitor.app.databinding.FragmentSettingsBinding
import com.watermonitor.app.utils.LocaleHelper
import com.watermonitor.app.utils.ThemeHelper

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var isUpdatingSwitch = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDarkModeToggle()
        setupLanguageSpinner()
        setupAboutButton()
    }

    private fun setupDarkModeToggle() {
        val currentMode = ThemeHelper.getSavedThemeMode(requireContext())
        isUpdatingSwitch = true
        binding.switchDarkMode.isChecked = currentMode == AppCompatDelegate.MODE_NIGHT_YES
        isUpdatingSwitch = false

        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener
            val mode = if (isChecked) ThemeHelper.DARK_MODE else ThemeHelper.LIGHT_MODE
            ThemeHelper.saveThemeMode(requireContext(), mode)
            ThemeHelper.applyTheme(mode)
        }
    }

    private fun setupLanguageSpinner() {
        val languages = resources.getStringArray(R.array.language_names)
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            languages
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLanguage.adapter = adapter

        val savedLang = LocaleHelper.getSavedLanguage(requireContext())
        val codes = resources.getStringArray(R.array.language_codes)
        val selectedIndex = codes.indexOf(savedLang).takeIf { it >= 0 } ?: 0
        binding.spinnerLanguage.setSelection(selectedIndex, false)

        binding.spinnerLanguage.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val newLang = codes[position]
                    val currentLang = LocaleHelper.getSavedLanguage(requireContext())
                    if (newLang != currentLang) {
                        LocaleHelper.saveLanguage(requireContext(), newLang)
                        requireActivity().recreate()
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    private fun setupAboutButton() {
        binding.cardAbout.setOnClickListener {
            findNavController().navigate(R.id.action_settingsFragment_to_aboutFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
