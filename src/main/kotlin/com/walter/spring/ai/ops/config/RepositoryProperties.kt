package com.walter.spring.ai.ops.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.InvalidPathException
import java.nio.file.Path

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
}
