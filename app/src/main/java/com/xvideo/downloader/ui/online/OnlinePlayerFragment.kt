package com.xvideo.downloader.ui.online

import android.animation.ObjectAnimator
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.xvideo.downloader.App
import com.xvideo.downloader.R
import com.xvideo.downloader.data.model.VideoFormat
import com.xvideo.downloader.databinding.FragmentOnlinePlayerBinding
import com.xvideo.downloader.ui.player.PlayerActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class OnlinePlayerFragment : Fragment() {

    private var _binding: FragmentOnlinePlayerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OnlinePlayerViewModel by viewModels()
    private lateinit var recentAdapter: RecentUrlAdapter
    private lateinit var formatAdapter: VideoFormatAdapter
    private var isHistoryExpanded = true
    private var currentUrl = ""

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
        binding.btnPaste.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrEmpty()) {
                binding.etUrl.setText(text)
                detectVideoFormats(text)
            } else {
                showSnackbar(getString(R.string.clipboard_empty))
            }
        }

        binding.btnPlay.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                playVideo(url)
            } else {
                showSnackbar(getString(R.string.please_enter_url))
            }
        }

        binding.btnDownload.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                startQuickDownload(url)
            } else {
                showSnackbar(getString(R.string.please_enter_url))
            }
        }

        binding.etUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val url = binding.etUrl.text.toString().trim()
                if (url.isNotEmpty() && url != currentUrl) {
                    detectVideoFormats(url)
                }
            }
        }

        formatAdapter = VideoFormatAdapter { format ->
            startQuickDownload(format.url)
        }
        binding.rvVideoFormats.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = formatAdapter
        }

        binding.layoutHistoryHeader.setOnClickListener {
            toggleHistory()
        }

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

    private fun detectVideoFormats(url: String) {
        currentUrl = url
        val formats = mutableListOf<VideoFormat>()

        val extension = url.substringAfterLast(".", "").lowercase()
        val isVideoExtension = extension in listOf("mp4", "webm", "avi", "mkv", "mov", "3gp", "flv", "m4v", "m3u8")

        if (isVideoExtension) {
            val quality = when {
                url.contains("1080") || url.contains("hd") -> "1080p HD"
                url.contains("720") -> "720p HD"
                url.contains("480") -> "480p SD"
                url.contains("360") || url.contains("sd") -> "360p SD"
                else -> "Original"
            }

            val format = when (extension) {
                "mp4" -> "MP4"
                "webm" -> "WebM"
                "avi" -> "AVI"
                "mkv" -> "MKV"
                "mov" -> "MOV"
                "3gp" -> "3GP"
                "flv" -> "FLV"
                "m4v" -> "M4V"
                "m3u8" -> "HLS"
                else -> extension.uppercase()
            }

            formats.add(VideoFormat(
                quality = quality,
                url = url,
                format = format
            ))
        }

        if (url.contains("m3u8") || url.contains("master.m3u8")) {
            formats.add(0, VideoFormat(
                quality = "HLS Stream",
                url = url,
                format = "M3U8"
            ))
        }

        if (formats.isNotEmpty()) {
            binding.cardDetectedFormats.visibility = View.VISIBLE
            formatAdapter.submitList(formats)
        } else {
            binding.cardDetectedFormats.visibility = View.GONE
        }
    }

    private fun startQuickDownload(url: String) {
        try {
            val videoInfo = com.xvideo.downloader.data.model.VideoInfo(
                tweetId = "",
                tweetUrl = url,
                authorName = "Online Video",
                authorUsername = "online",
                tweetText = "",
                thumbnailUrl = "",
                videoUrl = url,
                hasVideo = true
            )

            val variant = com.xvideo.downloader.data.model.VideoVariant(
                url = url,
                quality = "Original",
                bitrate = 0
            )

            App.downloadManager.startDownload(videoInfo, variant)
            showSnackbar(getString(R.string.downloading_video))
        } catch (e: Exception) {
            showSnackbar("Download error: ${e.message}")
        }
    }

    private fun toggleHistory() {
        isHistoryExpanded = !isHistoryExpanded

        if (isHistoryExpanded) {
            binding.rvRecentUrls.visibility = View.VISIBLE
            animateArrow(0f, 90f)
        } else {
            binding.rvRecentUrls.visibility = View.GONE
            animateArrow(90f, 0f)
        }
    }

    private fun animateArrow(fromDegrees: Float, toDegrees: Float) {
        ObjectAnimator.ofFloat(binding.ivHistoryArrow, "rotation", fromDegrees, toDegrees).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            start()
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
