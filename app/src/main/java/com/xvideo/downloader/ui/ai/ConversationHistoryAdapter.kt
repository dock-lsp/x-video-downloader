package com.xvideo.downloader.ui.ai

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xvideo.downloader.R
import com.xvideo.downloader.data.local.database.entity.AiConversationEntity

class ConversationHistoryAdapter(
    private val onClick: (AiConversationEntity) -> Unit,
    private val onDelete: (AiConversationEntity) -> Unit
) : ListAdapter<AiConversationEntity, ConversationHistoryAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<AiConversationEntity>() {
        override fun areItemsTheSame(oldItem: AiConversationEntity, newItem: AiConversationEntity) =
            oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: AiConversationEntity, newItem: AiConversationEntity) =
            oldItem == newItem
    }
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ai_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvConversationTitle)
        private val tvMessageCount: TextView = itemView.findViewById(R.id.tvMessageCount)
        private val tvDate: TextView = itemView.findViewById(R.id.tvConversationDate)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteConversation)

        fun bind(conversation: AiConversationEntity) {
            tvTitle.text = conversation.title
            tvMessageCount.text = "${conversation.messageCount} 条消息"
            tvDate.text = formatTimeAgo(conversation.updatedAt)

            itemView.setOnClickListener { onClick(conversation) }
            btnDelete.setOnClickListener { onDelete(conversation) }
        }

        private fun formatTimeAgo(timestamp: Long): String {
            val diff = System.currentTimeMillis() - timestamp
            return when {
                diff < 60_000 -> "刚刚"
                diff < 3600_000 -> "${diff / 60_000} 分钟前"
                diff < 86400_000 -> "${diff / 3600_000} 小时前"
                else -> "${diff / 86400_000} 天前"
            }
        }
    }
}
