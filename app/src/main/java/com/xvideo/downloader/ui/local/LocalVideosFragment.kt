package com.xvideo.downloader.ui.local

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.decode.VideoFrameDecoder
import com.google.android.material.snackbar.Snackbar
import com.xvideo.downloader.R
import com.xvideo.downloader.databinding.FragmentLocalVideosBinding
import com.xvideo.downloader.ui.player.PlayerActivity
import com.xvideo.downloader.util.FileUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LocalVideosFragment : Fragment() {

    private var _binding: FragmentLocalVideosBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LocalVideosViewModel by viewModels()
    private lateinit var adapter: LocalVideosAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLocalVideosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupUI()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadVideos()
    }

    private fun setupRecyclerView() {
        adapter = LocalVideosAdapter(
            onPlay = { video -> openPlayer(video) },
            onMore = { video, anchor -> showPopupMenu(video, anchor) }
        )

        binding.rvVideos.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@LocalVideosFragment.adapter
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadVideos()
        }
    }

    private fun setupUI() {
        binding.btnSort.setOnClickListener { view ->
            showSortMenu(view)
        }
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.apply {
            add(0, 1, 0, "Date (Newest)")
            add(0, 2, 0, "Date (Oldest)")
            add(0, 3, 0, "Size (Largest)")
            add(0, 4, 0, "Size (Smallest)")
            add(0, 5, 0, "Name (A-Z)")
            add(0, 6, 0, "Name (Z-A)")
        }
        popup.setOnMenuItemClickListener { item ->
            val sortOrder = when (item.itemId) {
                1 -> SortOrder.DATE_DESC
                2 -> SortOrder.DATE_ASC
                3 -> SortOrder.SIZE_DESC
                4 -> SortOrder.SIZE_ASC
                5 -> SortOrder.NAME_ASC
                6 -> SortOrder.NAME_DESC
                else -> SortOrder.DATE_DESC
            }
            viewModel.setSortOrder(sortOrder)
            true
        }
        popup.show()
    }

    private fun showPopupMenu(video: LocalVideo, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.apply {
            add(0, 1, 0, "Play")
            add(0, 2, 0, "Share")
            add(0, 3, 0, "Delete")
            add(0, 4, 0, "Details")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openPlayer(video)
                2 -> shareVideo(video)
                3 -> viewModel.deleteVideo(video)
                4 -> showVideoDetails(video)
            }
            true
        }
        popup.show()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.videos.collectLatest { videos ->
                        adapter.submitList(videos)
                        binding.tvEmpty.isVisible = videos.isEmpty()
                        binding.rvVideos.isVisible = videos.isNotEmpty()
                    }
                }

                launch {
                    viewModel.isLoading.collectLatest { isLoading ->
                        binding.swipeRefresh.isRefreshing = isLoading
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

    private fun openPlayer(video: LocalVideo) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, video.path)
            putExtra(PlayerActivity.EXTRA_IS_STREAMING, false)
        }
        startActivity(intent)
    }

    private fun shareVideo(video: LocalVideo) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, video.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Video"))
    }

    private fun showVideoDetails(video: LocalVideo) {
        val details = """
            Name: ${video.name}
            Path: ${video.path}
            Size: ${FileUtils.formatFileSize(video.size)}
            Duration: ${FileUtils.formatDuration(video.duration)}
            Resolution: ${video.width}x${video.height}
        """.trimIndent()
        showSnackbar(details)
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class LocalVideosAdapter(
    private val onPlay: (LocalVideo) -> Unit,
    private val onMore: (LocalVideo, View) -> Unit
) : androidx.recyclerview.widget.ListAdapter<LocalVideo, LocalVideosAdapter.ViewHolder>(
    object : androidx.recyclerview.widget.DiffUtil.ItemCallback<LocalVideo>() {
        override fun areItemsTheSame(oldItem: LocalVideo, newItem: LocalVideo) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: LocalVideo, newItem: LocalVideo) = oldItem == newItem
    }
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = com.xvideo.downloader.databinding.ItemLocalVideoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: com.xvideo.downloader.databinding.ItemLocalVideoBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

        fun bind(video: LocalVideo) {
            binding.apply {
                tvName.text = video.name
                tvSize.text = FileUtils.formatFileSize(video.size)
                tvDuration.text = FileUtils.formatDuration(video.duration)
                tvResolution.text = if (video.width > 0) "${video.width}x${video.height}" else "Unknown"

                // Load video thumbnail
                ivThumbnail.load(video.uri) {
                    crossfade(true)
                    decoderFactory { result, options, _ ->
                        VideoFrameDecoder(result.source, options)
                    }
                    placeholder(R.drawable.ic_video_placeholder)
                    error(R.drawable.ic_video_placeholder)
                }

                root.setOnClickListener { onPlay(video) }
                btnMore.setOnClickListener { onMore(video, it) }
            }
        }
    }
}
