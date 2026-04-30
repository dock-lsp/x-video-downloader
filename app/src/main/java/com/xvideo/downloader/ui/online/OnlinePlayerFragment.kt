package com.xvideo.downloader.ui.online

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.xvideo.downloader.R
import com.xvideo.downloader.databinding.FragmentOnlinePlayerBinding
import com.xvideo.downloader.ui.player.PlayerActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class OnlinePlayerFragment : Fragment() {

    private var _binding: FragmentOnlinePlayerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OnlinePlayerViewModel by viewModels()
    private lateinit var recentAdapter: RecentUrlAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnlinePlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        observeState()
    }

    private fun setupUI() {
        // Paste button
        binding.btnPaste.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrEmpty()) {
                binding.etUrl.setText(text)
            } else {
                showSnackbar(getString(R.string.clipboard_empty))
            }
        }

        // Play button
        binding.btnPlay.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                playVideo(url)
            } else {
                showSnackbar(getString(R.string.please_enter_url))
            }
        }

        // Clear history button
        binding.btnClearHistory.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.clear_history_title)
                .setMessage(R.string.clear_history_message)
                .setPositiveButton(R.string.clear) { _, _ ->
                    viewModel.clearHistory()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // Recent URLs RecyclerView
        recentAdapter = RecentUrlAdapter(
            onClick = { url ->
                binding.etUrl.setText(url.url)
                playVideo(url.url)
            },
            onDelete = { url ->
                viewModel.removeFromHistory(url.url)
            }
        )

        binding.rvRecentUrls.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = recentAdapter
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.recentUrls.collectLatest { urls ->
                        recentAdapter.submitList(urls)
                        binding.layoutRecentHistory.isVisible = urls.isNotEmpty()
                    }
                }
            }
        }
    }

    private fun playVideo(url: String) {
        // Validate URL
        if (!isValidUrl(url)) {
            showSnackbar(getString(R.string.error_invalid_url_general))
            return
        }

        // Add to history
        viewModel.addToHistory(url)

        // Open player
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, url)
            putExtra(PlayerActivity.EXTRA_IS_STREAMING, true)
        }
        startActivity(intent)
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            val uri = android.net.Uri.parse(url)
            uri.scheme != null && (uri.scheme == "http" || uri.scheme == "https")
        } catch (_: Exception) {
            false
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
