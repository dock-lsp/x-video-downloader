package com.xvideo.downloader.ui.online

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xvideo.downloader.databinding.ItemRecentUrlBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentUrlAdapter(
    private val onClick: (OnlinePlayerViewModel.RecentUrl) -> Unit,
    private val onDelete: (OnlinePlayerViewModel.RecentUrl) -> Unit
) : ListAdapter<OnlinePlayerViewModel.RecentUrl, RecentUrlAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<OnlinePlayerViewModel.RecentUrl>() {
        override fun areItemsTheSame(
            oldItem: OnlinePlayerViewModel.RecentUrl,
            newItem: OnlinePlayerViewModel.RecentUrl
        ) = oldItem.url == newItem.url

        override fun areContentsTheSame(
            oldItem: OnlinePlayerViewModel.RecentUrl,
            newItem: OnlinePlayerViewModel.RecentUrl
        ) = oldItem == newItem
    }
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentUrlBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemRecentUrlBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: OnlinePlayerViewModel.RecentUrl) {
            binding.apply {
                tvTitle.text = item.title
                tvUrl.text = item.url
                tvTime.text = formatTime(item.timestamp)

                root.setOnClickListener { onClick(item) }
                btnDelete.setOnClickListener { onDelete(item) }
            }
        }

        private fun formatTime(timestamp: Long): String {
            if (timestamp == 0L) return ""
            val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }
}
