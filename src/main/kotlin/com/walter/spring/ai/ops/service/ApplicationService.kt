package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_APPLICATIONS
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class ApplicationService(
    private val redisTemplate: StringRedisTemplate
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

    fun addApp(name: String) {
        runCatching {
            redisTemplate.opsForSet().add(REDIS_KEY_APPLICATIONS, name)
        }.getOrElse { e ->
            log.warn("Failed to add app '{}' to Set '{}' — deleting stale key and retrying. cause: {}", name, REDIS_KEY_APPLICATIONS, e.message)
            redisTemplate.delete(REDIS_KEY_APPLICATIONS)
            redisTemplate.opsForSet().add(REDIS_KEY_APPLICATIONS, name)
        }
    }

    fun removeApp(name: String) {
        redisTemplate.opsForSet().remove(REDIS_KEY_APPLICATIONS, name)
    }
}
