package com.xvideo.downloader.ui.batch

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.xvideo.downloader.R
import com.xvideo.downloader.databinding.FragmentBatchDownloadBinding
import com.xvideo.downloader.databinding.ItemBatchResultBinding
import com.xvideo.downloader.util.UrlUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BatchDownloadFragment : Fragment() {

    private var _binding: FragmentBatchDownloadBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BatchDownloadViewModel by viewModels()
    private val adapter = BatchResultAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBatchDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        observeState()
    }

    private fun setupUI() {
        binding.rvBatchResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvBatchResults.adapter = adapter

        // Paste from clipboard
        binding.btnPasteFromClipboard.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrEmpty()) {
                binding.etBatchUrls.setText(text)
            } else {
                Snackbar.make(binding.root, R.string.clipboard_empty, Snackbar.LENGTH_SHORT).show()
            }
        }

        // Clear
        binding.btnClearUrls.setOnClickListener {
            binding.etBatchUrls.text?.clear()
            adapter.submitList(emptyList())
            binding.layoutProgress.isVisible = false
        }

        // Start batch download
        binding.btnStartBatch.setOnClickListener {
            val urlsText = binding.etBatchUrls.text?.toString() ?: ""
            val urls = urlsText.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (urls.isEmpty()) {
                Snackbar.make(binding.root, "请输入至少一个链接", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Validate URLs
            val validUrls = urls.filter { UrlUtils.isValidTwitterUrl(it) }
            val invalidCount = urls.size - validUrls.size

            if (validUrls.isEmpty()) {
                Snackbar.make(binding.root, "未找到有效的 Twitter/X 链接", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (invalidCount > 0) {
                Snackbar.make(binding.root, "已跳过 $invalidCount 个无效链接", Snackbar.LENGTH_SHORT).show()
            }

            viewModel.startBatchDownload(validUrls)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.batchState.collectLatest { state ->
                        when (state) {
                            is BatchDownloadState.Idle -> {
                                binding.layoutProgress.isVisible = false
                            }
                            is BatchDownloadState.Processing -> {
                                binding.layoutProgress.isVisible = true
                                binding.progressBar.max = state.total
                                binding.progressBar.progress = state.completed
                                binding.tvProgressStatus.text = "进度: ${state.completed}/${state.total}"
                                adapter.submitList(state.results.toList())
                                binding.rvBatchResults.scrollToPosition(state.results.size - 1)
                            }
                            is BatchDownloadState.Done -> {
                                binding.layoutProgress.isVisible = true
                                binding.progressBar.max = state.total
                                binding.progressBar.progress = state.total
                                binding.tvProgressStatus.text = "完成: ${state.successCount} 成功, ${state.failCount} 失败"
                                adapter.submitList(state.results.toList())
                            }
                        }
                    }
                }

                launch {
                    viewModel.toastMessage.collectLatest { msg ->
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class BatchResultAdapter : RecyclerView.Adapter<BatchResultAdapter.ViewHolder>() {

    private var items: List<BatchResultItem> = emptyList()

    fun submitList(newItems: List<BatchResultItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBatchResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    class ViewHolder(private val binding: ItemBatchResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: BatchResultItem) {
            binding.tvUrl.text = item.url
            binding.tvStatusText.text = item.statusText
            binding.progressSmall.isVisible = item.isLoading

            val tint = when {
                item.isSuccess -> android.graphics.Color.parseColor("#4CAF50")
                item.isError -> android.graphics.Color.parseColor("#F44336")
                else -> android.graphics.Color.parseColor("#6750A4")
            }
            binding.ivStatus.setColorFilter(tint)

            val icon = when {
                item.isSuccess -> R.drawable.ic_download
                item.isError -> R.drawable.ic_close
                else -> R.drawable.ic_download
            }
            binding.ivStatus.setImageResource(icon)
        }
    }
}
