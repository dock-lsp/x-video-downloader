package com.xvideo.downloader.ui.downloads

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
import com.google.android.material.snackbar.Snackbar
import com.xvideo.downloader.R
import com.xvideo.downloader.data.local.database.entity.DownloadHistoryEntity
import com.xvideo.downloader.data.model.DownloadTask
import com.xvideo.downloader.data.model.DownloadTaskState
import com.xvideo.downloader.databinding.FragmentDownloadsBinding
import com.xvideo.downloader.ui.player.PlayerActivity
import com.xvideo.downloader.util.FileUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DownloadsViewModel by viewModels()
    private lateinit var activeAdapter: ActiveDownloadsAdapter
    private lateinit var historyAdapter: DownloadHistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerViews()
        observeState()
    }

    private fun setupRecyclerViews() {
        activeAdapter = ActiveDownloadsAdapter(
            onPause = { viewModel.pauseDownload(it.id) },
            onResume = { viewModel.resumeDownload(it.id) },
            onCancel = { viewModel.cancelDownload(it.id) },
            onPlay = { task ->
                openPlayer(task.videoInfo.videoVariants.firstOrNull()?.url ?: return@ActiveDownloadsAdapter)
            }
        )

        historyAdapter = DownloadHistoryAdapter(
            onPlay = { item ->
                item.filePath?.let { path ->
                    if (FileUtils.fileExists(path)) {
                        openLocalFile(path)
                    } else {
                        showSnackbar(getString(R.string.file_not_found))
                    }
                }
            },
            onDelete = { viewModel.deleteDownload(it.id) },
            onShare = { shareFile(it) }
        )

        binding.rvActiveDownloads.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = activeAdapter
        }

        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = historyAdapter
        }

        binding.swipeRefresh.setOnRefreshListener {
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.activeDownloads.collectLatest { downloads ->
                        activeAdapter.submitList(downloads)
                        binding.tvActiveEmpty.isVisible = downloads.isEmpty()
                        binding.rvActiveDownloads.isVisible = downloads.isNotEmpty()
                    }
                }

                launch {
                    viewModel.completedDownloads.collectLatest { history ->
                        historyAdapter.submitList(history)
                        binding.tvHistoryEmpty.isVisible = history.isEmpty()
                        binding.rvHistory.isVisible = history.isNotEmpty()
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

    private fun openPlayer(url: String) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, url)
            putExtra(PlayerActivity.EXTRA_IS_STREAMING, true)
        }
        startActivity(intent)
    }

    private fun openLocalFile(path: String) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, path)
            putExtra(PlayerActivity.EXTRA_IS_STREAMING, false)
        }
        startActivity(intent)
    }

    private fun shareFile(item: DownloadHistoryEntity) {
        item.filePath?.let { path ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(path))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_video)))
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

// Active Downloads Adapter
class ActiveDownloadsAdapter(
    private val onPause: (DownloadTask) -> Unit,
    private val onResume: (DownloadTask) -> Unit,
    private val onCancel: (DownloadTask) -> Unit,
    private val onPlay: (DownloadTask) -> Unit
) : androidx.recyclerview.widget.ListAdapter<DownloadTask, ActiveDownloadsAdapter.ViewHolder>(
    object : androidx.recyclerview.widget.DiffUtil.ItemCallback<DownloadTask>() {
        override fun areItemsTheSame(oldItem: DownloadTask, newItem: DownloadTask) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DownloadTask, newItem: DownloadTask) = oldItem == newItem
    }
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = com.xvideo.downloader.databinding.ItemActiveDownloadBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: com.xvideo.downloader.databinding.ItemActiveDownloadBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

        fun bind(task: DownloadTask) {
            binding.apply {
                tvAuthor.text = task.videoInfo.authorName
                tvQuality.text = task.variant.getQualityLabel()
                tvProgress.text = "${task.progress}%"

                progressBar.progress = task.progress
                progressBar.max = 100

                when (task.state) {
                    DownloadTaskState.DOWNLOADING -> {
                        btnPauseResume.setImageResource(android.R.drawable.ic_media_pause)
                        btnPauseResume.setOnClickListener { onPause(task) }
                    }
                    DownloadTaskState.PAUSED -> {
                        btnPauseResume.setImageResource(android.R.drawable.ic_media_play)
                        btnPauseResume.setOnClickListener { onResume(task) }
                    }
                    else -> {}
                }

                btnCancel.setOnClickListener { onCancel(task) }
            }
        }
    }
}

// History Adapter
class DownloadHistoryAdapter(
    private val onPlay: (DownloadHistoryEntity) -> Unit,
    private val onDelete: (DownloadHistoryEntity) -> Unit,
    private val onShare: (DownloadHistoryEntity) -> Unit
) : androidx.recyclerview.widget.ListAdapter<DownloadHistoryEntity, DownloadHistoryAdapter.ViewHolder>(
    object : androidx.recyclerview.widget.DiffUtil.ItemCallback<DownloadHistoryEntity>() {
        override fun areItemsTheSame(oldItem: DownloadHistoryEntity, newItem: DownloadHistoryEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DownloadHistoryEntity, newItem: DownloadHistoryEntity) = oldItem == newItem
    }
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = com.xvideo.downloader.databinding.ItemDownloadHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: com.xvideo.downloader.databinding.ItemDownloadHistoryBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadHistoryEntity) {
            binding.apply {
                tvAuthor.text = item.authorName
                tvUsername.text = "@${item.authorUsername}"
                tvQuality.text = item.quality
                tvFileSize.text = if (item.fileSize > 0) FileUtils.formatFileSize(item.fileSize) else ""
                tvDate.text = item.completedAt?.let { FileUtils.formatDuration(System.currentTimeMillis() - it) } ?: ""

                btnPlay.setOnClickListener { onPlay(item) }
                btnShare.setOnClickListener { onShare(item) }
                btnDelete.setOnClickListener { onDelete(item) }
            }
        }
    }
}
