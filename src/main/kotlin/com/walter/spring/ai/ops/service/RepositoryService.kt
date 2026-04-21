package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_CODE_RISK_PREFIX
import com.walter.spring.ai.ops.record.CodeRiskIssue
import com.walter.spring.ai.ops.record.CodeRiskRecord
import com.walter.spring.ai.ops.service.dto.CodeChunk
import com.walter.spring.ai.ops.util.zSetPushWithTtl
import com.walter.spring.ai.ops.util.zSetRangeAllDesc
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString

@Service
class RepositoryService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${analysis.data-retention-hours:120}") private val retentionHours: Long,
    @Value("\${analysis.maximum-view-count:5}") private val maximumViewCount: Long,
) {
    private val allowedExtensions = setOf(
        "kt", "kts", "java", "js", "ts", "tsx", "jsx",
        "py", "go", "rb", "cs", "php", "yml", "yaml",
        "xml", "json", "sql", "properties"
    )

    private val excludedDirectoryNames = setOf(
        "node_modules", "vendor", "target", "build", "dist",
        ".git", ".idea", ".vscode", "__pycache__", "bin",
        "obj", "out", "logs", "coverage", ".gradle", "md"
    )

    fun scanAllAtOnce(appName: String, gitUrl: String, branch: String = ""): String {
        val sourcePath = cloneRepository(appName, gitUrl, branch)
        val sourceFiles = collectSourceFiles(sourcePath)
        return buildBundle(sourcePath, sourceFiles)
    }

    fun buildBundle(repositoryRoot: Path, files: List<Path>): String {
        val sb = StringBuilder()
        sb.appendLine("# Repository source code bundle")
        sb.appendLine("# Path: ${repositoryRoot.toAbsolutePath()}")
        sb.appendLine("# Root: ${repositoryRoot.fileName}")
        sb.appendLine()

        files.forEach { file ->
            val relativePath = repositoryRoot.relativize(file).invariantSeparatorsPathString
            sb.appendLine("## File: $relativePath")
            sb.appendLine("```")
            sb.appendLine(file.toFile().readText())
            sb.appendLine("```")
            sb.appendLine()
        }
        return sb.toString()
    }

    fun collectSourceFiles(repositoryRoot: Path): List<Path> {
        return Files.walk(repositoryRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { path -> path.none { part -> excludedDirectoryNames.contains(part.toString()) } }
                .filter { path -> allowedExtensions.contains(path.extension.lowercase()) }
                .filter { path -> Files.size(path) <= 300_000 } // Skip files larger than 300KB
                .sorted()
                .toList()
        }
    }

    fun createChunks(repositoryRoot: Path, files: List<Path>): List<CodeChunk> {
        val grouped = files.groupBy { file ->
            val relativePath = repositoryRoot.relativize(file)
            relativePath.parent?.fileName?.toString() ?: "root"
        }
        return grouped.map { (label, groupedFiles) ->
            CodeChunk(label, buildBundle(repositoryRoot, groupedFiles))
        }
    }

    fun cloneRepository(appName: String, gitUrl: String, branch: String = "", accessToken: String? = null): Path {
        val tempDir = Files.createTempDirectory("repository-scan-$appName")
        Git.cloneRepository()
            .setURI(gitUrl)
            .setDirectory(tempDir.toFile())
            .setCloneAllBranches(false)
            .apply { if (branch.isNotEmpty()) setBranch(branch) }
            .apply {
                if (!accessToken.isNullOrBlank()) {
                    setCredentialsProvider(UsernamePasswordCredentialsProvider("oauth2", accessToken))
                }
            }
            .setDepth(1)
            .call()
            .use { /* Cloned successfully, tempDir contains the repository */ }
        return tempDir
    }

    fun saveAnalyzedResult(appName: String, gitUrl: String, branch: String, result: String, issues: List<CodeRiskIssue> = emptyList()): CodeRiskRecord {
        val key = "$REDIS_KEY_CODE_RISK_PREFIX$appName"
        val record = CodeRiskRecord(LocalDateTime.now(), appName, gitUrl, branch, result, issues.ifEmpty { null })
        redisTemplate.zSetPushWithTtl(key, objectMapper.writeValueAsString(record), retentionHours)
        return record
    }

    fun getCodeRiskRecords(application: String): List<CodeRiskRecord> {
        val key = "$REDIS_KEY_CODE_RISK_PREFIX$application"
        return redisTemplate.zSetRangeAllDesc(key)
            .mapNotNull { runCatching { objectMapper.readValue(it, CodeRiskRecord::class.java) }.getOrNull() }
            .let { if (maximumViewCount > 0) it.take(maximumViewCount.toInt()) else it }
    }

    fun hasCodeRiskRecords(application: String): Boolean {
        val key = "$REDIS_KEY_CODE_RISK_PREFIX$application"
        return (redisTemplate.opsForZSet().zCard(key) ?: 0L) > 0L
    }
}