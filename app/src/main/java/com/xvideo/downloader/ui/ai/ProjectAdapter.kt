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
import com.xvideo.downloader.data.local.database.entity.GeneratedProjectEntity
import com.xvideo.downloader.util.FileUtils

class ProjectAdapter(
    private val onClick: (GeneratedProjectEntity) -> Unit,
    private val onDelete: (GeneratedProjectEntity) -> Unit
) : ListAdapter<GeneratedProjectEntity, ProjectAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<GeneratedProjectEntity>() {
        override fun areItemsTheSame(oldItem: GeneratedProjectEntity, newItem: GeneratedProjectEntity) =
            oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: GeneratedProjectEntity, newItem: GeneratedProjectEntity) =
            oldItem == newItem
    }
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_generated_project, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvProjectName)
        private val tvDescription: TextView = itemView.findViewById(R.id.tvProjectDescription)
        private val tvFileCount: TextView = itemView.findViewById(R.id.tvFileCount)
        private val tvDate: TextView = itemView.findViewById(R.id.tvProjectDate)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteProject)

        fun bind(project: GeneratedProjectEntity) {
            tvName.text = project.name
            tvDescription.text = project.description
            tvFileCount.text = "${project.fileCount} 个文件"
            tvDate.text = FileUtils.formatDuration(System.currentTimeMillis() - project.createdAt)

            itemView.setOnClickListener { onClick(project) }
            btnDelete.setOnClickListener { onDelete(project) }
        }
    }
}
