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
import com.xvideo.downloader.data.local.DownloadManager
import com.xvideo.downloader.data.model.DownloadTask
import com.xvideo.downloader.databinding.FragmentOnlinePlayerBinding
import com.xvideo.downloader.ui.player.PlayerActivity
import com.xvideo.downloader.util.FileUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class OnlinePlayerFragment : Fragment() {

    private var _binding: FragmentOnlinePlayerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OnlinePlayerViewModel by viewModels()
    private lateinit var recentAdapter: RecentUrlAdapter
    private lateinit var downloadManager: DownloadManager

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
        downloadManager = DownloadManager.getInstance(requireContext())
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

        // Download button
        binding.btnDownload.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                startDownload(url)
            } else {
                showSnackbar(getString(R.string.please_enter_url))
            }
        }

        // URL text change listener - show download button when URL is entered
        binding.etUrl.setOnFocusChangeListener { _, hasFocus ->
            updateDownloadButtonVisibility()
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
                updateDownloadButtonVisibility()
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

    private fun updateDownloadButtonVisibility() {
        val url = binding.etUrl.text.toString().trim()
        binding.btnDownload.isVisible = url.isNotEmpty() && isValidUrl(url)
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

        // Observe text changes
        binding.etUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateDownloadButtonVisibility()
            }
        }
    }

    private fun playVideo(url: String) {
        if (!isValidUrl(url)) {
            showSnackbar(getString(R.string.error_invalid_url_general))
            return
        }

        viewModel.addToHistory(url)

        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, url)
            putExtra(PlayerActivity.EXTRA_IS_STREAMING, true)
        }
        startActivity(intent)
    }

    private fun startDownload(url: String) {
        if (!isValidUrl(url)) {
            showSnackbar(getString(R.string.error_invalid_url_general))
            return
        }

        binding.progressDownload.isVisible = true
        binding.progressDownload.isIndeterminate = true

        viewModel.addToHistory(url)

        val fileName = "online_${System.currentTimeMillis()}.mp4"
        val outputDir = FileUtils.getVideoDirectory(requireContext())
        val outputFile = File(outputDir, fileName)

        val task = DownloadTask(
            id = System.currentTimeMillis(),
            url = url,
            fileName = fileName,
            filePath = outputFile.absolutePath,
            totalBytes = 0,
            downloadedBytes = 0,
            status = com.xvideo.downloader.data.model.DownloadStatus.DOWNLOADING,
            createdAt = System.currentTimeMillis()
        )

        downloadManager.startDownload(task)

        showSnackbar(getString(R.string.download_started))

        // Reset UI after short delay
        binding.root.postDelayed({
            binding.progressDownload.isVisible = false
        }, 1000)
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
