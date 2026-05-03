package com.xvideo.downloader.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.xvideo.downloader.R
import com.xvideo.downloader.data.local.database.entity.AiMessageEntity
import com.xvideo.downloader.data.local.database.entity.GeneratedProjectEntity
import com.xvideo.downloader.databinding.FragmentAiBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AiFragment : Fragment() {

    private var _binding: FragmentAiBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AiViewModel by viewModels()
    private lateinit var messageAdapter: AiMessageAdapter
    private lateinit var projectAdapter: ProjectAdapter
    private lateinit var historyAdapter: ConversationHistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        observeState()
    }

    private fun setupUI() {
        // Message list
        messageAdapter = AiMessageAdapter(
            onCodeClick = { message -> showCodeDialog(message) },
            onCopyClick = { content -> copyToClipboard(content) }
        )
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(context).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }

        // Project list
        projectAdapter = ProjectAdapter(
            onClick = { project -> showProjectDetail(project) },
            onDelete = { project -> viewModel.deleteProject(project) }
        )
        binding.rvProjects.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = projectAdapter
        }

        // History list
        historyAdapter = ConversationHistoryAdapter(
            onClick = { conversation ->
                viewModel.loadConversation(conversation.id)
                showChatPanel()
            },
            onDelete = { conversation ->
                viewModel.deleteConversation(conversation.id)
            }
        )
        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = historyAdapter
        }

        // Send button
        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                viewModel.sendMessage(text)
                binding.etInput.text?.clear()
            }
        }

        // New conversation button
        binding.btnNewChat.setOnClickListener {
            viewModel.newConversation()
            showChatPanel()
        }

        // Bottom navigation within AI section
        binding.btnChat.setOnClickListener { showChatPanel() }
        binding.btnProjects.setOnClickListener { showProjectsPanel() }
        binding.btnHistory.setOnClickListener { showHistoryPanel() }

        // Settings button
        binding.btnAiSettings.setOnClickListener { showSettingsDialog() }

        // Set initial selection
        binding.btnChat.isSelected = true

        // Empty state - start new chat
        binding.btnStartChat.setOnClickListener {
            if (!viewModel.isConfigured()) {
                showSettingsDialog()
            } else {
                viewModel.newConversation()
                showChatPanel()
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collectLatest { messages ->
                        messageAdapter.submitList(messages)
                        if (messages.isNotEmpty()) {
                            binding.rvMessages.scrollToPosition(maxOf(0, messages.size - 1))
                        }
                        binding.layoutEmpty.isVisible = messages.isEmpty()
                        binding.rvMessages.isVisible = messages.isNotEmpty()
                    }
                }

                launch {
                    viewModel.conversations.collectLatest { conversations ->
                        historyAdapter.submitList(conversations)
                    }
                }

                launch {
                    viewModel.projects.collectLatest { projects ->
                        projectAdapter.submitList(projects)
                        binding.tvProjectsEmpty.isVisible = projects.isEmpty()
                        binding.rvProjects.isVisible = projects.isNotEmpty()
                    }
                }

                launch {
                    viewModel.isLoading.collectLatest { loading ->
                        binding.progressBar.isVisible = loading
                        binding.btnSend.isEnabled = !loading
                    }
                }

                launch {
                    viewModel.errorMessage.collectLatest { error ->
                        if (isAdded) {
                            Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }

                launch {
                    viewModel.statusMessage.collectLatest { msg ->
                        if (isAdded) {
                            Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun showChatPanel() {
        binding.layoutChat.isVisible = true
        binding.layoutProjects.isVisible = false
        binding.layoutHistory.isVisible = false
        binding.btnChat.isSelected = true
        binding.btnProjects.isSelected = false
        binding.btnHistory.isSelected = false
    }

    private fun showProjectsPanel() {
        binding.layoutChat.isVisible = false
        binding.layoutProjects.isVisible = true
        binding.layoutHistory.isVisible = false
        binding.btnChat.isSelected = false
        binding.btnProjects.isSelected = true
        binding.btnHistory.isSelected = false
    }

    private fun showHistoryPanel() {
        binding.layoutChat.isVisible = false
        binding.layoutProjects.isVisible = false
        binding.layoutHistory.isVisible = true
        binding.btnChat.isSelected = false
        binding.btnProjects.isSelected = false
        binding.btnHistory.isSelected = true
    }

    private fun showCodeDialog(message: AiMessageEntity) {
        val codeBlocks = message.codeBlocks?.split(",") ?: return
        if (codeBlocks.isEmpty()) return

        val fileNames = codeBlocks.map { it.substringAfterLast("/") }

        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("生成的文件")
            .setItems(fileNames.toTypedArray()) { _, which ->
                val filePath = codeBlocks[which]
                val content = viewModel.readProjectFile(filePath)
                if (content != null) {
                    showFileEditor(filePath, content)
                } else {
                    if (isAdded) {
                        Snackbar.make(binding.root, "无法读取文件", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showFileEditor(filePath: String, content: String) {
        if (!isAdded) return

        val editText = EditText(requireContext()).apply {
            setText(content)
            setHorizontallyScrolling(true)
            textSize = 12f
            val padding = resources.getDimensionPixelSize(R.dimen.spacing_m)
            setPadding(padding, padding, padding, padding)
        }

        val scrollView = ScrollView(requireContext()).apply {
            addView(editText, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(filePath.substringAfterLast("/"))
            .setView(scrollView)
            .setPositiveButton("保存") { _, _ ->
                viewModel.saveFileContent(filePath, editText.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showProjectDetail(project: GeneratedProjectEntity) {
        val files = viewModel.getProjectFiles(project.directoryPath)
        if (files.isEmpty()) {
            if (isAdded) {
                Snackbar.make(binding.root, "项目无文件", Snackbar.LENGTH_SHORT).show()
            }
            return
        }

        val fileNames = files.map { "${it.relativePath} (${formatSize(it.size)})" }

        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(project.name)
            .setMessage("文件数: ${files.size}\n路径: ${project.directoryPath}")
            .setItems(fileNames.toTypedArray()) { _, which ->
                val file = files[which]
                val content = viewModel.readProjectFile(file.path)
                if (content != null) {
                    showFileEditor(file.path, content)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showSettingsDialog() {
        if (!isAdded) return

        val prefs = requireContext().getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = resources.getDimensionPixelSize(R.dimen.spacing_m)
            setPadding(padding, padding, padding, padding)
        }

        val etBaseUrl = EditText(requireContext()).apply {
            hint = "API Base URL"
            setText(prefs.getString("base_url", "https://api.openai.com/v1"))
        }
        val etApiKey = EditText(requireContext()).apply {
            hint = "API Key"
            setText(prefs.getString("api_key", ""))
        }
        val etModel = EditText(requireContext()).apply {
            hint = "模型名称"
            setText(prefs.getString("model", "gpt-4o-mini"))
        }

        layout.addView(etBaseUrl, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 })
        layout.addView(etApiKey, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 })
        layout.addView(etModel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("AI 设置")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                prefs.edit()
                    .putString("base_url", etBaseUrl.text.toString().trim())
                    .putString("api_key", etApiKey.text.toString().trim())
                    .putString("model", etModel.text.toString().trim())
                    .apply()
                if (isAdded) {
                    Snackbar.make(binding.root, "设置已保存", Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        if (!isAdded) return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("code", text))
        Snackbar.make(binding.root, "已复制到剪贴板", Snackbar.LENGTH_SHORT).show()
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> "${bytes / (1024 * 1024)}MB"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
