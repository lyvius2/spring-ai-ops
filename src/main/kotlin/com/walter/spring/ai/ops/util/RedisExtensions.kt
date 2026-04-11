package com.walter.spring.ai.ops.util

import org.springframework.data.redis.core.StringRedisTemplate

fun StringRedisTemplate.listPushIfAbsent(key: String, value: String) {
    val existing = opsForList().range(key, 0, -1) ?: emptyList()
    if (value !in existing) {
        opsForList().rightPush(key, value)
    }
}
