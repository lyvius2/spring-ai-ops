package com.walter.spring.ai.ops.service

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class ApplicationService(
    private val redisTemplate: StringRedisTemplate
) {
    companion object {
        private const val KEY = "apps"
    }

    fun getApps(): List<String> =
        redisTemplate.opsForList().range(KEY, 0, -1) ?: emptyList()

    fun addApp(name: String) {
        val existing = redisTemplate.opsForList().range(KEY, 0, -1) ?: emptyList()
        if (name in existing) {
            return
        }
        redisTemplate.opsForList().rightPush(KEY, name)
    }

    fun removeApp(name: String) {
        redisTemplate.opsForList().remove(KEY, 0, name)
    }
}
