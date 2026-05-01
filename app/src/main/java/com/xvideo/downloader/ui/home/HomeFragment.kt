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
import com.xvideo.downloader.data.model.M3u8Stream
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

    // Track which quality source we're using
    private var currentM3u8Streams: List<M3u8Stream> = emptyList()
    private var currentVideoVariants: List<VideoVariant> = emptyList()

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
            btnPaste.setOnClickListener {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!text.isNullOrEmpty()) {
                    etUrl.setText(text)
                } else {
                    showSnackbar(getString(R.string.clipboard_empty))
                }
            }

            btnParse.setOnClickListener {
                val url = etUrl.text.toString()
                if (url.isNotEmpty()) {
                    viewModel.parseUrl(url)
                } else {
                    showSnackbar(getString(R.string.please_enter_url))
                }
            }
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
                    viewModel.m3u8Streams.collectLatest { streams ->
                        currentM3u8Streams = streams
                        if (streams.isNotEmpty()) {
                            setupM3u8QualityOptions(streams)
                        }
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
                    tvStatusText.text = getString(R.string.parsing)
                    tvStatusText.isVisible = true
                }
                is VideoParseState.Error -> {
                    tvError.text = state.message
                    tvError.isVisible = true
                    cardResult.isVisible = false
                    tvStatusText.isVisible = false
                }
                is VideoParseState.Success -> {
                    tvStatusText.isVisible = false
                }
                else -> {
                    tvStatusText.isVisible = false
                }
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

            // Show stream type indicator
            if (videoInfo.hasM3u8Stream()) {
                tvStreamType.text = "HLS (M3U8)"
                tvStreamType.isVisible = true
            } else {
                tvStreamType.text = "Direct MP4"
                tvStreamType.isVisible = true
            }

            // Setup quality options based on what's available
            currentVideoVariants = videoInfo.getAvailableQualities()
            if (currentM3u8Streams.isEmpty() && currentVideoVariants.isNotEmpty()) {
                setupDirectQualityOptions(videoInfo)
            }
            // m3u8 options will be set when m3u8Streams flow emits
        }
    }

    /**
     * Setup quality dropdown for direct MP4 variants (fallback path).
     */
    private fun setupDirectQualityOptions(videoInfo: VideoInfo) {
        val qualities = currentVideoVariants
        if (qualities.isEmpty()) return

        val qualityLabels = qualities.map {
            "${it.getQualityLabel()} - ${FileUtils.formatFileSize(it.bitrate.toLong() * 60)}"
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
                it.getQualityLabel() == binding.spinnerQuality.text.toString().split(" - ").first()
            }
            if (selectedIndex >= 0) {
                viewModel.startDownload(qualities[selectedIndex])
            }
        }

        binding.btnPlayOnline.setOnClickListener {
            val selectedIndex = qualities.indexOfFirst {
                it.getQualityLabel() == binding.spinnerQuality.text.toString().split(" - ").first()
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
                    showSnackbar(getString(R.string.opening_player))
                }
            }
        } else {
            binding.btnDownloadGif.visibility = View.GONE
        }
    }

    /**
     * Setup quality dropdown for m3u8 HLS streams (new flow).
     * Shows resolution/bandwidth options parsed from the m3u8 master playlist.
     */
    private fun setupM3u8QualityOptions(streams: List<M3u8Stream>) {
        val qualityLabels = streams.map { stream ->
            val sizeHint = if (stream.bandwidth > 0) {
                " (~${FileUtils.formatFileSize(stream.bandwidth * 180 / 8)})"  // ~3 min estimate
            } else ""
            "${stream.quality}${stream.resolution?.let { " ($it)" } ?: ""}$sizeHint"
        }

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            qualityLabels
        )
        binding.spinnerQuality.setAdapter(adapter)
        binding.spinnerQuality.setText(qualityLabels.firstOrNull() ?: "", false)

        binding.btnDownload.setOnClickListener {
            val selectedIndex = qualityLabels.indexOf(binding.spinnerQuality.text.toString())
            if (selectedIndex >= 0) {
                viewModel.startM3u8Download(streams[selectedIndex])
            }
        }

        binding.btnPlayOnline.setOnClickListener {
            val selectedIndex = qualityLabels.indexOf(binding.spinnerQuality.text.toString())
            if (selectedIndex >= 0) {
                val stream = streams[selectedIndex]
                val variant = VideoVariant(stream.videoUrl, (stream.bandwidth / 1000).toInt(), "video/mp4")
                viewModel.currentVideoInfo.value?.let { info ->
                    openPlayer(info, variant)
                }
            }
        }

        binding.btnDownloadGif.visibility = View.GONE
    }

    private fun updateDownloadUI(state: DownloadState) {
        binding.apply {
            when (state) {
                is DownloadState.Downloading -> {
                    downloadProgressLayout.isVisible = true
                    progressDownload.progress = state.progress
                    tvProgress.text = "${state.progress}%"
                    tvStatusText.isVisible = false
                }
                is DownloadState.Completed -> {
                    downloadProgressLayout.isVisible = false
                    showSnackbar(getString(R.string.download_completed))
                }
                is DownloadState.Error -> {
                    downloadProgressLayout.isVisible = false
                    showSnackbar(getString(R.string.error_format, state.message))
                }
                is DownloadState.Parsing -> {
                    tvStatusText.text = getString(R.string.parsing)
                    tvStatusText.isVisible = true
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
