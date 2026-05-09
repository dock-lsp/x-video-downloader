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

    private val aiApiService = runCatching {
        AiApiService.getInstance()
    }.onFailure { e ->
        Log.e(TAG, "Failed to initialize AiApiService", e)
        null
    }.getOrNull()

    private val codeGenRepo = runCatching {
        CodeGenerationRepository.getInstance(application)
    }.onFailure { e ->
        Log.e(TAG, "Failed to initialize CodeGenerationRepository", e)
        null
    }.getOrNull()

    private val database = runCatching {
        App.getInstance().database
    }.onFailure { e ->
        Log.e(TAG, "Failed to get database", e)
        null
    }.getOrNull()

    private val conversationDao = runCatching {
        database?.aiConversationDao()
    }.onFailure { e ->
        Log.e(TAG, "Failed to get AiConversationDao", e)
        null
    }.getOrNull()

    private val messageDao = runCatching {
        database?.aiMessageDao()
    }.onFailure { e ->
        Log.e(TAG, "Failed to get AiMessageDao", e)
        null
    }.getOrNull()

    private val projectDao = runCatching {
        database?.generatedProjectDao()
    }.onFailure { e ->
        Log.e(TAG, "Failed to get GeneratedProjectDao", e)
        null
    }.getOrNull()

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    private val _messages = MutableStateFlow<List<AiMessageEntity>>(emptyList())
    val messages: StateFlow<List<AiMessageEntity>> = _messages.asStateFlow()

    val conversations: StateFlow<List<AiConversationEntity>> =
        conversationDao?.getAllConversations()
            ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
            ?: MutableStateFlow(emptyList())

    val projects: StateFlow<List<GeneratedProjectEntity>> =
        projectDao?.getAllProjects()
            ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
            ?: MutableStateFlow(emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    private val _statusMessage = MutableSharedFlow<String>()
    val statusMessage: SharedFlow<String> = _statusMessage.asSharedFlow()

    init {
        if (database == null) {
            Log.w(TAG, "Database not available - AI features may not work")
        }
        viewModelScope.launch {
            runCatching {
                val convos = conversationDao?.getAllConversations()?.first()
                if (!convos.isNullOrEmpty()) {
                    loadConversation(convos.first().id)
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to auto-load conversation", e)
            }
        }
    }

    fun newConversation() {
        val dao = conversationDao ?: run {
            viewModelScope.launch { _errorMessage.emit("数据库未初始化") }
            return
        }
        viewModelScope.launch {
            runCatching {
                val conversation = AiConversationEntity(
                    id = UUID.randomUUID().toString(),
                    title = "新对话"
                )
                dao.insert(conversation)
                _currentConversationId.value = conversation.id
                _messages.value = emptyList()
            }.onFailure { e ->
                Log.e(TAG, "Failed to create conversation", e)
                _errorMessage.emit("创建对话失败: ${e.message}")
            }
        }
    }

    fun loadConversation(conversationId: String) {
        val dao = messageDao ?: run {
            viewModelScope.launch { _errorMessage.emit("数据库未初始化") }
            return
        }
        viewModelScope.launch {
            runCatching {
                _currentConversationId.value = conversationId
                val msgs = dao.getMessagesList(conversationId)
                _messages.value = msgs
            }.onFailure { e ->
                Log.e(TAG, "Failed to load conversation", e)
                _errorMessage.emit("加载对话失败: ${e.message}")
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            runCatching {
                messageDao?.deleteByConversation(conversationId)
                conversationDao?.deleteById(conversationId)
                if (_currentConversationId.value == conversationId) {
                    _currentConversationId.value = null
                    _messages.value = emptyList()
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to delete conversation", e)
                _errorMessage.emit("删除对话失败: ${e.message}")
            }
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return
        if (_isLoading.value) return
        if (conversationDao == null || messageDao == null) {
            viewModelScope.launch { _errorMessage.emit("数据库未初始化") }
            return
        }
        if (aiApiService == null) {
            viewModelScope.launch { _errorMessage.emit("AI 服务未初始化") }
            return
        }

        viewModelScope.launch {
            val convId = _currentConversationId.value ?: run {
                val conversation = AiConversationEntity(
                    id = UUID.randomUUID().toString(),
                    title = content.take(50).replace("\n", " ")
                )
                runCatching { conversationDao.insert(conversation) }
                    .onFailure { e -> Log.e(TAG, "Failed to create conversation", e) }
                _currentConversationId.value = conversation.id
                conversation.id
            }

            runCatching {
                val userMessage = AiMessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = "user",
                    content = content
                )
                messageDao.insert(userMessage)
                _messages.value = _messages.value + userMessage

                val msgCount = messageDao.getMessageCount(convId)
                if (msgCount == 1) {
                    val title = content.take(50).replace("\n", " ")
                    val existing = conversationDao.getById(convId)
                    existing?.let {
                        conversationDao.update(it.copy(title = title, updatedAt = System.currentTimeMillis()))
                    }
                }

                val contextMessages = _messages.value.map { it.role to it.content }
                _isLoading.value = true

                val result = aiApiService.chat(contextMessages)
                result.fold(
                    onSuccess = { response ->
                        val codeResult = codeGenRepo?.parseAndCreateFiles(response, conversationId = convId)
                            ?: CodeGenerationRepository.CodeGenResult(emptyList(), null, "CodeGenRepo not available")
                        val hasCode = codeResult.createdFiles.isNotEmpty()

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

                        conversationDao.updateMessageCount(convId, _messages.value.size)

                        codeResult.project?.let { project ->
                            projectDao?.insert(project)
                            _statusMessage.emit("✅ ${codeResult.message}")
                        }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "AI request failed", error)
                        _errorMessage.emit(error.message ?: "AI 请求失败")
                    }
                )
            }.onFailure { e ->
                Log.e(TAG, "Send message failed", e)
                _errorMessage.emit("发送失败: ${e.message}")
            }.also {
                _isLoading.value = false
            }
        }
    }

    fun isConfigured(): Boolean = aiApiService?.isConfigured() ?: false

    fun deleteProject(project: GeneratedProjectEntity) {
        viewModelScope.launch {
            runCatching {
                codeGenRepo?.deleteProject(project.directoryPath)
                projectDao?.deleteById(project.id)
            }.onFailure { e ->
                Log.e(TAG, "Failed to delete project", e)
                _errorMessage.emit("删除项目失败: ${e.message}")
            }
        }
    }

    fun getProjectFiles(projectDir: String): List<CodeGenerationRepository.ProjectFile> {
        return codeGenRepo?.listProjectFiles(projectDir) ?: emptyList()
    }

    fun readProjectFile(filePath: String): String? {
        return codeGenRepo?.readFile(filePath)
    }

    fun saveFileContent(filePath: String, content: String) {
        viewModelScope.launch {
            val success = codeGenRepo?.saveFile(filePath, content) ?: false
            if (success) {
                _statusMessage.emit("💾 已保存")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "AiViewModel cleared")
    }

    companion object {
        private const val TAG = "AiViewModel"
    }
}
