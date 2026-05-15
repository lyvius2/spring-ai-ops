package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_APP_GIT
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_APPLICATIONS
import com.walter.spring.ai.ops.controller.dto.AppUpdateRequest
import com.walter.spring.ai.ops.service.dto.AppConfig
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class ApplicationService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ApplicationService::class.java)

    fun getApps(): List<String> {
        return runCatching {
            redisTemplate.opsForSet().members(REDIS_KEY_APPLICATIONS)?.toList() ?: emptyList()
        }.getOrElse { e ->
            log.warn("Failed to read '{}' as Set type — key may hold a wrong type. Deleting and returning empty list. cause: {}", REDIS_KEY_APPLICATIONS, e.message)
            redisTemplate.delete(REDIS_KEY_APPLICATIONS)
            emptyList()
        }
    }

    fun addApp(appUpdateRequest: AppUpdateRequest) {
        val appName = appUpdateRequest.name
        runCatching {
            redisTemplate.opsForSet().add(REDIS_KEY_APPLICATIONS, appName)
        }.getOrElse { e ->
            log.warn("Failed to add app '{}' to Set '{}' — deleting stale key and retrying. cause: {}", appName, REDIS_KEY_APPLICATIONS, e.message)
            redisTemplate.delete(REDIS_KEY_APPLICATIONS)
            redisTemplate.opsForSet().add(REDIS_KEY_APPLICATIONS, appName)
        }
        if (appUpdateRequest.gitUrl != null || appUpdateRequest.deployBranch != null) {
            saveAppConfig(appUpdateRequest)
        }
    }

    fun removeApp(name: String) {
        redisTemplate.opsForSet().remove(REDIS_KEY_APPLICATIONS, name)
        redisTemplate.delete("$REDIS_KEY_APP_GIT$name")
    }

    fun getGitUrl(name: String): String? = getAppConfig(name)?.gitUrl

    fun getAppConfig(name: String): AppConfig? {
        val value = redisTemplate.opsForValue().get("$REDIS_KEY_APP_GIT$name") ?: return null
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
        val key = "$REDIS_KEY_APP_GIT${appUpdateRequest.name}"
        if (gitUrl.isNullOrBlank()) {
            redisTemplate.delete(key)
        } else {
            validateGitUrl(gitUrl)
            val config = AppConfig(appUpdateRequest)
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(config))
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
            redisTemplate.opsForSet().remove(REDIS_KEY_APPLICATIONS, oldName)
            redisTemplate.opsForSet().add(REDIS_KEY_APPLICATIONS, newName)
            redisTemplate.delete("$REDIS_KEY_APP_GIT$oldName")
        }
        saveAppConfig(appUpdateRequest)
    }
}
