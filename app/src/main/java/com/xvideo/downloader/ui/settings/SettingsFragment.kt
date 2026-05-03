package com.xvideo.downloader.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.xvideo.downloader.R
import com.xvideo.downloader.databinding.FragmentSettingsBinding
import com.xvideo.downloader.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

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
        setupUI()
        observeState()
    }

    private fun setupUI() {
        // Theme selection
        binding.itemTheme.setOnClickListener {
            showThemeDialog()
        }

        // Language selection
        binding.itemLanguage.setOnClickListener {
            showLanguageDialog()
        }

        // Download quality
        binding.itemQuality.setOnClickListener {
            showQualityDialog()
        }

        // Auto-play
        binding.switchAutoPlay.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoPlay(isChecked)
        }

        // Speed limit
        binding.itemSpeedLimit.setOnClickListener {
            showSpeedLimitDialog()
        }

        // Clear cache
        binding.itemClearCache.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.clear_cache_title)
                .setMessage(R.string.clear_cache_message)
                .setPositiveButton(R.string.clear) { _, _ ->
                    viewModel.clearCache()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // Version info
        binding.tvVersion.text = getString(R.string.version_format, viewModel.getAppVersion())

        // About
        binding.itemAbout.setOnClickListener {
            showAboutDialog()
        }
    }

    private fun showThemeDialog() {
        val themes = SettingsViewModel.THEME_OPTIONS.toTypedArray()
        val currentIndex = when (viewModel.themeMode.value) {
            AppCompatDelegate.MODE_NIGHT_NO -> 1
            AppCompatDelegate.MODE_NIGHT_YES -> 2
            else -> 0
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.theme)
            .setSingleChoiceItems(themes, currentIndex) { dialog, which ->
                val mode = when (which) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                viewModel.setThemeMode(mode)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLanguageDialog() {
        val languages = SettingsViewModel.LANGUAGE_OPTIONS.toTypedArray()
        val currentLang = viewModel.appLanguage.value
        val currentIndex = SettingsViewModel.LANGUAGE_VALUES.indexOf(currentLang).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_language)
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val selectedLang = SettingsViewModel.LANGUAGE_VALUES[which]
                viewModel.setAppLanguage(selectedLang)
                dialog.dismiss()
                
                // Recreate activity to apply language change
                activity?.let { act ->
                    val intent = Intent(act, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    act.finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showQualityDialog() {
        val qualities = SettingsViewModel.QUALITY_OPTIONS.toTypedArray()
        val currentIndex = qualities.indexOf(viewModel.downloadQuality.value).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.default_quality)
            .setSingleChoiceItems(qualities, currentIndex) { dialog, which ->
                viewModel.setDownloadQuality(qualities[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSpeedLimitDialog() {
        val labels = SettingsViewModel.SPEED_LIMIT_LABELS.toTypedArray()
        val currentLimit = viewModel.speedLimit.value
        val currentIndex = SettingsViewModel.SPEED_LIMIT_VALUES.indexOf(currentLimit).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.speed_limit)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                viewModel.setSpeedLimit(SettingsViewModel.SPEED_LIMIT_VALUES[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.about_content, viewModel.getAppVersion()))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.themeMode.collectLatest { mode ->
                        binding.tvThemeValue.text = when (mode) {
                            AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.theme_light)
                            AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.theme_dark)
                            else -> getString(R.string.theme_system)
                        }
                    }
                }

                launch {
                    viewModel.appLanguage.collectLatest { lang ->
                        val index = SettingsViewModel.LANGUAGE_VALUES.indexOf(lang).coerceAtLeast(0)
                        binding.tvLanguageValue.text = SettingsViewModel.LANGUAGE_OPTIONS[index]
                    }
                }

                launch {
                    viewModel.downloadQuality.collectLatest { quality ->
                        binding.tvQualityValue.text = quality
                    }
                }

                launch {
                    viewModel.autoPlay.collectLatest { enabled ->
                        binding.switchAutoPlay.isChecked = enabled
                    }
                }

                launch {
                    viewModel.speedLimit.collectLatest { limit ->
                        val index = SettingsViewModel.SPEED_LIMIT_VALUES.indexOf(limit).coerceAtLeast(0)
                        binding.tvSpeedLimitValue.text = SettingsViewModel.SPEED_LIMIT_LABELS[index]
                    }
                }

                launch {
                    viewModel.toastMessage.collectLatest { message ->
                        showSnackbar(message)
                    }
                }
            }
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
