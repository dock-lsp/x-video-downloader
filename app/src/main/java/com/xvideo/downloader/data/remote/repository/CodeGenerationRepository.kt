package com.xvideo.downloader.data.remote.repository

import android.content.Context
import android.util.Log
import com.xvideo.downloader.data.local.database.entity.GeneratedProjectEntity
import java.io.File
import java.util.UUID

/**
 * Manages AI-generated code files and projects.
 * Creates files according to AI-specified directory structure.
 */
class CodeGenerationRepository(private val context: Context) {

    companion object {
        private const val TAG = "CodeGenRepo"
        private const val PROJECTS_DIR = "ai_projects"

        @Volatile
        private var instance: CodeGenerationRepository? = null

        fun getInstance(context: Context): CodeGenerationRepository {
            return instance ?: synchronized(this) {
                instance ?: CodeGenerationRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Get the root directory for all AI-generated projects.
     */
    fun getProjectsRoot(): File {
        val dir = File(context.filesDir, PROJECTS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Parse code blocks from AI response and create files.
     * @param response AI response text containing code blocks with file path comments
     * @param projectName Optional project name (auto-detected if null)
     * @param conversationId Associated conversation ID
     * @return CodeGenResult with created files and project info
     */
    suspend fun parseAndCreateFiles(
        response: String,
        projectName: String? = null,
        conversationId: String? = null
    ): CodeGenResult {
        val codeBlocks = extractCodeBlocks(response)
        if (codeBlocks.isEmpty()) {
            return CodeGenResult(emptyList(), null, "未检测到代码块")
        }

        // Detect project name from first file path or use provided name
        val detectedProject = projectName
            ?: detectProjectName(codeBlocks)
            ?: "project_${System.currentTimeMillis()}"

        val projectDir = File(getProjectsRoot(), detectedProject)
        if (!projectDir.exists()) projectDir.mkdirs()

        val createdFiles = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (block in codeBlocks) {
            try {
                val filePath = block.filePath ?: continue
                val file = File(projectDir, filePath)

                // Security: prevent path traversal
                val canonicalProject = projectDir.canonicalPath
                val canonicalFile = file.canonicalPath
                if (!canonicalFile.startsWith(canonicalProject)) {
                    errors.add("安全拒绝: 路径越界 ${block.filePath}")
                    continue
                }

                // Create parent directories
                file.parentFile?.let { parent ->
                    if (!parent.exists()) parent.mkdirs()
                }

                // Write file content
                file.writeText(block.code)
                createdFiles.add(file.absolutePath)
                Log.d(TAG, "Created file: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create file: ${block.filePath}", e)
                errors.add("创建文件失败 ${block.filePath}: ${e.message}")
            }
        }

        // Create project entity only if files were created
        val project = if (createdFiles.isNotEmpty()) {
            GeneratedProjectEntity(
                id = UUID.randomUUID().toString(),
                name = detectedProject,
                description = "AI 生成的项目",
                directoryPath = projectDir.absolutePath,
                fileCount = createdFiles.size,
                conversationId = conversationId
            )
        } else null

        val message = buildString {
            append("已创建 ${createdFiles.size} 个文件")
            if (errors.isNotEmpty()) {
                append("，${errors.size} 个失败")
            }
        }

        return CodeGenResult(createdFiles, project, message, errors)
    }

    /**
     * Extract code blocks from markdown-formatted AI response.
     */
    private fun extractCodeBlocks(response: String): List<CodeBlock> {
        val blocks = mutableListOf<CodeBlock>()
        val regex = Regex("```(\\w*)\\s*\\n([\\s\\S]*?)```", RegexOption.MULTILINE)

        regex.findAll(response).forEach { match ->
            val language = match.groupValues[1].ifBlank { "text" }
            val rawCode = match.groupValues[2].trim()

            // Try to extract file path from first line comment
            val filePath = extractFilePath(rawCode)
            val code = if (filePath != null) {
                // Remove the file path comment line(s)
                rawCode.lines().dropWhile {
                    val t = it.trim()
                    t.startsWith("//") || (t.startsWith("#") && t.contains("文件路径"))
                }.joinToString("\n").trim()
            } else {
                rawCode
            }

            if (code.isNotBlank()) {
                blocks.add(CodeBlock(language, filePath, code))
            }
        }

        return blocks
    }

    /**
     * Extract file path from code comment.
     * Supports formats:
     * // 文件路径: path/to/file
     * // filepath: path/to/file
     * // path: path/to/file
     * # 文件路径: path/to/file
     */
    private fun extractFilePath(code: String): String? {
        val firstLines = code.lines().take(5)
        for (line in firstLines) {
            val trimmed = line.trim()
            val patterns = listOf(
                Regex("//\\s*文件路径[:：]\\s*(.+)"),
                Regex("//\\s*[Ff]ile[Pp]ath[:：]\\s*(.+)"),
                Regex("//\\s*[Pp]ath[:：]\\s*(.+)"),
                Regex("#\\s*文件路径[:：]\\s*(.+)"),
                Regex("//\\s*(.+\\.(kt|java|xml|json|gradle|py|js|ts|html|css|sql|yaml|yml|md|txt|sh))\\s*$")
            )

            for (pattern in patterns) {
                val match = pattern.find(trimmed)
                if (match != null) {
                    val path = match.groupValues[1].trim()
                    if (path.isNotBlank() && !path.contains(" ") && path.length < 200) {
                        return path
                    }
                }
            }
        }
        return null
    }

    /**
     * Detect project name from file paths.
     */
    private fun detectProjectName(blocks: List<CodeBlock>): String? {
        for (block in blocks) {
            val path = block.filePath ?: continue
            val parts = path.split("/")
            if (parts.size >= 2) {
                return parts[0]
            }
        }
        return null
    }

    /**
     * List all files in a project directory.
     */
    fun listProjectFiles(projectDir: String): List<ProjectFile> {
        val dir = File(projectDir)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        return dir.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                ProjectFile(
                    name = file.name,
                    path = file.absolutePath,
                    relativePath = file.relativeTo(dir).path,
                    size = file.length(),
                    lastModified = file.lastModified()
                )
            }
            .sortedBy { it.relativePath }
            .toList()
    }

    /**
     * Read file content.
     */
    fun readFile(filePath: String): String? {
        return try {
            val file = File(filePath)
            if (file.exists() && file.length() < 2 * 1024 * 1024) { // Max 2MB
                file.readText()
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file: $filePath", e)
            null
        }
    }

    /**
     * Update file content.
     */
    fun saveFile(filePath: String, content: String): Boolean {
        return try {
            val file = File(filePath)
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            file.writeText(content)
            Log.d(TAG, "Saved file: $filePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save file: $filePath", e)
            false
        }
    }

    /**
     * Delete a project and all its files.
     */
    fun deleteProject(projectDir: String): Boolean {
        return try {
            File(projectDir).deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete project: $projectDir", e)
            false
        }
    }

    data class CodeBlock(
        val language: String,
        val filePath: String?,
        val code: String
    )

    data class CodeGenResult(
        val createdFiles: List<String>,
        val project: GeneratedProjectEntity?,
        val message: String,
        val errors: List<String> = emptyList()
    )

    data class ProjectFile(
        val name: String,
        val path: String,
        val relativePath: String,
        val size: Long,
        val lastModified: Long
    )
}
