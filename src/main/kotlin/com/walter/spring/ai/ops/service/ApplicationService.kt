package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_APP_CONFIG
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_APPLICATIONS
import com.walter.spring.ai.ops.controller.dto.AppUpdateRequest
import com.walter.spring.ai.ops.connector.cache.CacheStorePort
import com.walter.spring.ai.ops.service.dto.AppConfig
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ApplicationService(
    private val cacheStorePort: CacheStorePort,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ApplicationService::class.java)

    fun getApps(): List<String> {
        return runCatching {
            cacheStorePort.getDataSet(REDIS_KEY_APPLICATIONS).toList()
        }.getOrElse { e ->
            log.warn("Failed to read '{}' as Set type — key may hold a wrong type. Deleting and returning empty list. cause: {}", REDIS_KEY_APPLICATIONS, e.message)
            cacheStorePort.delete(REDIS_KEY_APPLICATIONS)
            emptyList()
        }
    }

    fun addApp(appUpdateRequest: AppUpdateRequest) {
        val appName = appUpdateRequest.name
        runCatching {
            cacheStorePort.addToSet(REDIS_KEY_APPLICATIONS, appName)
        }.getOrElse { e ->
            log.warn("Failed to add app '{}' to Set '{}' — deleting stale key and retrying. cause: {}", appName, REDIS_KEY_APPLICATIONS, e.message)
            cacheStorePort.delete(REDIS_KEY_APPLICATIONS)
            cacheStorePort.addToSet(REDIS_KEY_APPLICATIONS, appName)
        }
        if (appUpdateRequest.gitUrl != null || appUpdateRequest.deployBranch != null) {
            saveAppConfig(appUpdateRequest)
        }
    }

    fun removeApp(name: String) {
        cacheStorePort.removeFromSet(REDIS_KEY_APPLICATIONS, name)
        cacheStorePort.delete("$REDIS_KEY_APP_CONFIG$name")
    }

    fun getGitUrl(name: String): String? = getAppConfig(name)?.gitUrl

    fun getAppConfig(name: String): AppConfig? {
        val value = cacheStorePort.get("$REDIS_KEY_APP_CONFIG$name") ?: return null
        return runCatching {
            objectMapper.readValue(value, AppConfig::class.java)
        }.getOrElse { e ->
            log.warn("Failed to parse git config for app '{}' — returning null. cause: {}", name, e.message)
            null
        }
    }

    fun getGitRepoByAppName(appName: String): String {
        return getGitUrl(appName)
            ?: throw IllegalStateException("Git repository URL is not configured for application '$appName'")
    }

    fun saveAppConfig(appUpdateRequest: AppUpdateRequest) {
        val gitUrl = appUpdateRequest.gitUrl
        if (!appUpdateRequest.deployBranch.isNullOrBlank() && gitUrl.isNullOrBlank()) {
            throw IllegalArgumentException("Git Repository URL is required when Deploy Branch is specified.")
        }
        val key = "$REDIS_KEY_APP_CONFIG${appUpdateRequest.name}"
        if (gitUrl.isNullOrBlank()) {
            cacheStorePort.delete(key)
        } else {
            validateGitUrl(gitUrl)
            val config = AppConfig(appUpdateRequest)
            cacheStorePort.set(key, objectMapper.writeValueAsString(config))
        }
    }

    private fun validateGitUrl(gitUrl: String) {
        require(gitUrl.startsWith("http://") || gitUrl.startsWith("https://")) {
            "Git URL must use HTTP or HTTPS protocol."
        }
    }

    fun updateApp(oldName: String, appUpdateRequest: AppUpdateRequest) {
        val newName = appUpdateRequest.name
        if (oldName != newName) {
            cacheStorePort.removeFromSet(REDIS_KEY_APPLICATIONS, oldName)
            cacheStorePort.addToSet(REDIS_KEY_APPLICATIONS, newName)
            cacheStorePort.delete("$REDIS_KEY_APP_CONFIG$oldName")
        }
        saveAppConfig(appUpdateRequest)
    }
}
