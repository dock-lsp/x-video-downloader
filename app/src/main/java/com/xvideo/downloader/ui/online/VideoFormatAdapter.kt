package com.xvideo.downloader.ui.online

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xvideo.downloader.data.model.VideoFormat
import com.xvideo.downloader.databinding.ItemVideoFormatBinding

class VideoFormatAdapter(
    private val onDownloadClick: (VideoFormat) -> Unit
) : ListAdapter<VideoFormat, VideoFormatAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVideoFormatBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemVideoFormatBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(format: VideoFormat) {
            binding.tvQuality.text = format.quality
            binding.tvFormat.text = format.format.uppercase()
            if (format.getSizeLabel().isNotEmpty()) {
                binding.tvSize.text = format.getSizeLabel()
            } else {
                binding.tvSize.text = ""
            }

            binding.btnDownload.setOnClickListener {
                onDownloadClick(format)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<VideoFormat>() {
        override fun areItemsTheSame(oldItem: VideoFormat, newItem: VideoFormat): Boolean {
            return oldItem.url == newItem.url
        }

        override fun areContentsTheSame(oldItem: VideoFormat, newItem: VideoFormat): Boolean {
            return oldItem == newItem
        }
    }
}
