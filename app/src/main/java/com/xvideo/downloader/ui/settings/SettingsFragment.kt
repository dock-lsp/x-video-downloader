package com.xvideo.downloader.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.xvideo.downloader.BuildConfig
import com.xvideo.downloader.databinding.FragmentSettingsBinding
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

        // Download quality
        binding.itemQuality.setOnClickListener {
            showQualityDialog()
        }

        // Auto-play
        binding.switchAutoPlay.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoPlay(isChecked)
        }

        // Clear cache
        binding.itemClearCache.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear Cache")
                .setMessage("This will delete all cached data. Continue?")
                .setPositiveButton("Clear") { _, _ ->
                    viewModel.clearCache()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Version info
        binding.tvVersion.text = "Version ${viewModel.getAppVersion()}"

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
            .setTitle("Theme")
            .setSingleChoiceItems(themes, currentIndex) { dialog, which ->
                val mode = when (which) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                viewModel.setThemeMode(mode)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showQualityDialog() {
        val qualities = SettingsViewModel.QUALITY_OPTIONS.toTypedArray()
        val currentIndex = qualities.indexOf(viewModel.downloadQuality.value).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Default Download Quality")
            .setSingleChoiceItems(qualities, currentIndex) { dialog, which ->
                viewModel.setDownloadQuality(qualities[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("X Video Downloader")
            .setMessage("""
                A powerful Twitter/X video downloader for Android.
                
                Features:
                • Download videos from Twitter/X
                • Multiple quality options (SD/HD/2K/4K)
                • Download GIFs
                • Built-in video player
                • Local video management
                
                Version: ${viewModel.getAppVersion()}
            """.trimIndent())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.themeMode.collectLatest { mode ->
                        binding.tvThemeValue.text = when (mode) {
                            AppCompatDelegate.MODE_NIGHT_NO -> "Light"
                            AppCompatDelegate.MODE_NIGHT_YES -> "Dark"
                            else -> "System"
                        }
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
