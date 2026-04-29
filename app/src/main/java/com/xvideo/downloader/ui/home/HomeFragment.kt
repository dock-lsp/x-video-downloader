package com.xvideo.downloader.ui.home

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.google.android.material.snackbar.Snackbar
import com.xvideo.downloader.R
import com.xvideo.downloader.data.model.DownloadState
import com.xvideo.downloader.data.model.VideoInfo
import com.xvideo.downloader.data.model.VideoVariant
import com.xvideo.downloader.data.remote.repository.VideoParseState
import com.xvideo.downloader.databinding.FragmentHomeBinding
import com.xvideo.downloader.ui.player.PlayerActivity
import com.xvideo.downloader.util.FileUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        observeState()
        handleIntent(requireActivity().intent)
    }


    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
            if (sharedText.contains("twitter.com") || sharedText.contains("x.com")) {
                binding.etUrl.setText(sharedText)
                viewModel.parseUrl(sharedText)
            }
        }
    }

    private fun setupUI() {
        binding.apply {
            // Paste button
            btnPaste.setOnClickListener {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!text.isNullOrEmpty()) {
                    etUrl.setText(text)
                } else {
                    showSnackbar("Clipboard is empty")
                }
            }

            // Parse button
            btnParse.setOnClickListener {
                val url = etUrl.text.toString()
                if (url.isNotEmpty()) {
                    viewModel.parseUrl(url)
                } else {
                    showSnackbar("Please enter a URL")
                }
            }

            // Quality selection handled dynamically
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.parseState.collectLatest { state ->
                        updateParseUI(state)
                    }
                }

                launch {
                    viewModel.downloadState.collectLatest { state ->
                        updateDownloadUI(state)
                    }
                }

                launch {
                    viewModel.currentVideoInfo.collectLatest { info ->
                        info?.let { updateVideoInfoUI(it) }
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

    private fun updateParseUI(state: VideoParseState) {
        binding.apply {
            progressBar.isVisible = state is VideoParseState.Loading
            cardResult.isVisible = state is VideoParseState.Success
            tvError.isVisible = state is VideoParseState.Error
            btnParse.isEnabled = state !is VideoParseState.Loading

            when (state) {
                is VideoParseState.Loading -> {
                    tvError.isVisible = false
                }
                is VideoParseState.Error -> {
                    tvError.text = state.message
                    tvError.isVisible = true
                    cardResult.isVisible = false
                }
                else -> {}
            }
        }
    }

    private fun updateVideoInfoUI(videoInfo: VideoInfo) {
        binding.apply {
            // Load thumbnail
            videoInfo.thumbnailUrl?.let { url ->
                ivThumbnail.load(url) {
                    crossfade(true)
                    placeholder(R.drawable.ic_video_placeholder)
                    error(R.drawable.ic_video_placeholder)
                }
            }

            // Author info
            tvAuthor.text = videoInfo.authorName
            tvUsername.text = "@${videoInfo.authorUsername}"
            tvTweetText.text = videoInfo.tweetText

            // Quality options
            setupQualityOptions(videoInfo)
        }
    }

    private fun setupQualityOptions(videoInfo: VideoInfo) {
        val qualities = videoInfo.getAvailableQualities()
        val qualityLabels = qualities.map { 
            "${it.getQualityLabel()} (${FileUtils.formatFileSize(it.bitrate.toLong() * 60)})" 
        }

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            qualityLabels
        )
        binding.spinnerQuality.setAdapter(adapter)
        binding.spinnerQuality.setText(qualityLabels.firstOrNull() ?: "", false)

        binding.btnDownload.setOnClickListener {
            val selectedIndex = qualities.indexOfFirst { 
                it.getQualityLabel() == binding.spinnerQuality.text.toString().split(" ").first()
            }
            if (selectedIndex >= 0) {
                viewModel.startDownload(qualities[selectedIndex])
            }
        }

        binding.btnPlayOnline.setOnClickListener {
            val selectedIndex = qualities.indexOfFirst { 
                it.getQualityLabel() == binding.spinnerQuality.text.toString().split(" ").first()
            }
            if (selectedIndex >= 0) {
                openPlayer(videoInfo, qualities[selectedIndex])
            }
        }

        // GIF option
        if (videoInfo.hasGif()) {
            binding.btnDownloadGif.visibility = View.VISIBLE
            binding.btnDownloadGif.setOnClickListener {
                videoInfo.gifVariants.firstOrNull()?.let { gif ->
                    showSnackbar("GIF download not yet implemented")
                }
            }
        } else {
            binding.btnDownloadGif.visibility = View.GONE
        }
    }

    private fun updateDownloadUI(state: DownloadState) {
        binding.apply {
            when (state) {
                is DownloadState.Downloading -> {
                    downloadProgressLayout.isVisible = true
                    progressDownload.progress = state.progress
                    tvProgress.text = "${state.progress}%"
                }
                is DownloadState.Completed -> {
                    downloadProgressLayout.isVisible = false
                    showSnackbar("Download completed!")
                }
                is DownloadState.Error -> {
                    downloadProgressLayout.isVisible = false
                    showSnackbar("Error: ${state.message}")
                }
                else -> {
                    downloadProgressLayout.isVisible = false
                }
            }
        }
    }

    private fun openPlayer(videoInfo: VideoInfo, variant: VideoVariant) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, variant.url)
            putExtra(PlayerActivity.EXTRA_VIDEO_INFO, videoInfo)
            putExtra(PlayerActivity.EXTRA_IS_STREAMING, true)
        }
        startActivity(intent)
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
