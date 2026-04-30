package com.walter.spring.ai.ops.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest

@ConfigurationProperties(prefix = "repository")
data class RepositoryProperties(
    val stored: Boolean = false,
    val localPath: String = "",
) {
    private val log = LoggerFactory.getLogger(RepositoryProperties::class.java)

    fun persistentStorageRoot(): Path? {
        if (!stored || localPath.isBlank()) {
            return null
        }
        return try {
            Path.of(localPath).toAbsolutePath().normalize()
        } catch (e: InvalidPathException) {
            log.error("Invalid localPath for repository storage: '{}', {}", localPath, e.message)
            null
        }
    }

    fun isPersistentStorageUsable(): Boolean = persistentStorageRoot() != null

    fun resolvePersistentRepositoryPath(applicationName: String, gitUrl: String): Path? {
        val root = persistentStorageRoot() ?: return null
        val directoryName = "${sanitizeApplicationName(applicationName)}-${sha256(gitUrl).take(12)}"
        val repositoryPath = root.resolve(directoryName).toAbsolutePath().normalize()
        return repositoryPath.takeIf { isSafePersistentRepositoryPath(it) }
    }

    fun isSafePersistentRepositoryPath(path: Path): Boolean {
        val root = persistentStorageRoot() ?: return false
        val normalizedPath = path.toAbsolutePath().normalize()
        return normalizedPath.startsWith(root) && normalizedPath != root
    }

    fun isExistingSafePersistentRepositoryPath(path: Path): Boolean {
        return isSafePersistentRepositoryPath(path) && Files.exists(path)
    }

    private fun sanitizeApplicationName(applicationName: String): String {
        val sanitized = applicationName
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
        return sanitized.ifBlank { "application" }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
