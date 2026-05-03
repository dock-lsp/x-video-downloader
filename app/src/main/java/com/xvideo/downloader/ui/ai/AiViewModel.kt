package com.xvideo.downloader.ui.ai

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xvideo.downloader.App
import com.xvideo.downloader.data.local.database.entity.AiConversationEntity
import com.xvideo.downloader.data.local.database.entity.AiMessageEntity
import com.xvideo.downloader.data.local.database.entity.GeneratedProjectEntity
import com.xvideo.downloader.data.remote.api.AiApiService
import com.xvideo.downloader.data.remote.repository.CodeGenerationRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

class AiViewModel(application: Application) : AndroidViewModel(application) {

    private val aiApiService = AiApiService.getInstance()
    private val codeGenRepo = CodeGenerationRepository.getInstance(application)
    private val database = App.getInstance().database
    private val conversationDao = database.aiConversationDao()
    private val messageDao = database.aiMessageDao()
    private val projectDao = database.generatedProjectDao()

    companion object {
        private const val TAG = "AiViewModel"
    }

    // Current conversation
    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    // Messages in current conversation
    private val _messages = MutableStateFlow<List<AiMessageEntity>>(emptyList())
    val messages: StateFlow<List<AiMessageEntity>> = _messages.asStateFlow()

    // All conversations (history)
    val conversations: StateFlow<List<AiConversationEntity>> =
        conversationDao.getAllConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Generated projects
    val projects: StateFlow<List<GeneratedProjectEntity>> =
        projectDao.getAllProjects()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error messages
    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    // Status messages
    private val _statusMessage = MutableSharedFlow<String>()
    val statusMessage: SharedFlow<String> = _statusMessage.asSharedFlow()

    // Code generation result
    private val _lastCodeGenResult = MutableStateFlow<CodeGenerationRepository.CodeGenResult?>(null)
    val lastCodeGenResult: StateFlow<CodeGenerationRepository.CodeGenResult?> = _lastCodeGenResult.asStateFlow()

    init {
        // Auto-start a new conversation if none exists
        viewModelScope.launch {
            val convos = conversationDao.getAllConversations().first()
            if (convos.isNotEmpty()) {
                loadConversation(convos.first().id)
            }
        }
    }

    /**
     * Start a new conversation.
     */
    fun newConversation() {
        viewModelScope.launch {
            val conversation = AiConversationEntity(
                id = UUID.randomUUID().toString(),
                title = "新对话"
            )
            conversationDao.insert(conversation)
            _currentConversationId.value = conversation.id
            _messages.value = emptyList()
        }
    }

    /**
     * Load an existing conversation.
     */
    fun loadConversation(conversationId: String) {
        viewModelScope.launch {
            _currentConversationId.value = conversationId
            val msgs = messageDao.getMessagesList(conversationId)
            _messages.value = msgs
        }
    }

    /**
     * Delete a conversation and its messages.
     */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            messageDao.deleteByConversation(conversationId)
            conversationDao.deleteById(conversationId)
            if (_currentConversationId.value == conversationId) {
                _currentConversationId.value = null
                _messages.value = emptyList()
            }
        }
    }

    /**
     * Send a message and get AI response.
     */
    fun sendMessage(content: String) {
        if (content.isBlank()) return
        if (_isLoading.value) return

        val conversationId = _currentConversationId.value
        if (conversationId == null) {
            newConversation()
        }

        viewModelScope.launch {
            val convId = _currentConversationId.value ?: return@launch

            // Save user message
            val userMessage = AiMessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = convId,
                role = "user",
                content = content
            )
            messageDao.insert(userMessage)
            _messages.value = _messages.value + userMessage

            // Update conversation title if first message
            val msgCount = messageDao.getMessageCount(convId)
            if (msgCount == 1) {
                val title = content.take(50).replace("\n", " ")
                val existing = conversationDao.getById(convId)
                if (existing != null) {
                    conversationDao.update(existing.copy(title = title, updatedAt = System.currentTimeMillis()))
                }
            }

            // Build context for AI
            val context = _messages.value.map { it.role to it.content }

            _isLoading.value = true

            try {
                val result = aiApiService.chat(context)
                result.fold(
                    onSuccess = { response ->
                        // Parse code blocks
                        val codeResult = codeGenRepo.parseAndCreateFiles(
                            response, conversationId = convId
                        )

                        val hasCode = codeResult.createdFiles.isNotEmpty()

                        // Save AI response
                        val aiMessage = AiMessageEntity(
                            id = UUID.randomUUID().toString(),
                            conversationId = convId,
                            role = "assistant",
                            content = response,
                            hasCode = hasCode,
                            codeBlocks = if (hasCode) codeResult.createdFiles.joinToString(",") else null
                        )
                        messageDao.insert(aiMessage)
                        _messages.value = _messages.value + aiMessage

                        // Update conversation
                        conversationDao.updateMessageCount(convId, _messages.value.size)

                        // Save project if files were created
                        if (codeResult.project != null) {
                            projectDao.insert(codeResult.project)
                            _lastCodeGenResult.value = codeResult
                            _statusMessage.emit("✅ ${codeResult.message}")
                        }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "AI request failed", error)
                        _errorMessage.emit(error.message ?: "AI 请求失败")
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Send message failed", e)
                _errorMessage.emit("发送失败: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Check if AI is configured.
     */
    fun isConfigured(): Boolean = aiApiService.isConfigured()

    /**
     * Get code generation repository for file operations.
     */
    fun getCodeGenRepo(): CodeGenerationRepository = codeGenRepo

    /**
     * Delete a generated project.
     */
    fun deleteProject(project: GeneratedProjectEntity) {
        viewModelScope.launch {
            codeGenRepo.deleteProject(project.directoryPath)
            projectDao.deleteById(project.id)
        }
    }

    /**
     * Get project files.
     */
    fun getProjectFiles(projectDir: String): List<CodeGenerationRepository.ProjectFile> {
        return codeGenRepo.listProjectFiles(projectDir)
    }

    /**
     * Read a file from a project.
     */
    fun readProjectFile(filePath: String): String? {
        return codeGenRepo.readFile(filePath)
    }

    /**
     * Auto-save file content.
     */
    fun saveFileContent(filePath: String, content: String) {
        viewModelScope.launch {
            val success = codeGenRepo.saveFile(filePath, content)
            if (success) {
                _statusMessage.emit("💾 已自动保存")
            }
        }
    }
}
