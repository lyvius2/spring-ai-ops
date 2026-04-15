package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_APPLICATIONS
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class ApplicationService(
    private val redisTemplate: StringRedisTemplate
) {
    fun getApps(): List<String> {
        return redisTemplate.opsForList().range(REDIS_KEY_APPLICATIONS, 0, -1) ?: emptyList()
    }

    fun addApp(name: String) {
        val existing = redisTemplate.opsForList().range(REDIS_KEY_APPLICATIONS, 0, -1) ?: emptyList()
        if (name in existing) {
            return
        }
        redisTemplate.opsForList().rightPush(REDIS_KEY_APPLICATIONS, name)
    }

    fun removeApp(name: String) {
        redisTemplate.opsForList().remove(REDIS_KEY_APPLICATIONS, 0, name)
    }
}
