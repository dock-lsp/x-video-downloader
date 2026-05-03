package com.xvideo.downloader.ui.ai

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xvideo.downloader.R
import com.xvideo.downloader.data.local.database.entity.AiMessageEntity

class AiMessageAdapter(
    private val onCodeClick: (AiMessageEntity) -> Unit,
    private val onCopyClick: (String) -> Unit
) : ListAdapter<AiMessageEntity, AiMessageAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<AiMessageEntity>() {
        override fun areItemsTheSame(oldItem: AiMessageEntity, newItem: AiMessageEntity) =
            oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: AiMessageEntity, newItem: AiMessageEntity) =
            oldItem == newItem
    }
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutRes = if (viewType == VIEW_TYPE_USER) {
            R.layout.item_ai_message_user
        } else {
            R.layout.item_ai_message_assistant
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).role == "user") VIEW_TYPE_USER else VIEW_TYPE_ASSISTANT
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvContent: TextView = itemView.findViewById(R.id.tvMessageContent)
        private val tvCodeIndicator: TextView? = itemView.findViewById(R.id.tvCodeIndicator)
        private val btnCopy: ImageButton? = itemView.findViewById(R.id.btnCopy)

        fun bind(message: AiMessageEntity) {
            tvContent.text = message.content

            // Show code indicator for assistant messages with code
            tvCodeIndicator?.let { indicator ->
                if (message.hasCode && message.role == "assistant") {
                    indicator.isVisible = true
                    indicator.text = "📄 包含代码文件 (点击查看)"
                    indicator.setOnClickListener { onCodeClick(message) }
                } else {
                    indicator.isVisible = false
                }
            }

            // Copy button
            btnCopy?.setOnClickListener {
                onCopyClick(message.content)
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_ASSISTANT = 1
    }
}
