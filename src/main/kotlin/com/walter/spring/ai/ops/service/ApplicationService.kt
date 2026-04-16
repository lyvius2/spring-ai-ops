package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_APPLICATIONS
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class ApplicationService(
    private val redisTemplate: StringRedisTemplate
) {
    fun getApps(): List<String> {
        return redisTemplate.opsForSet().members(REDIS_KEY_APPLICATIONS)?.toList() ?: emptyList()
    }

    fun addApp(name: String) {
        redisTemplate.opsForSet().add(REDIS_KEY_APPLICATIONS, name)
    }

    fun removeApp(name: String) {
        redisTemplate.opsForSet().remove(REDIS_KEY_APPLICATIONS, name)
    }
}
