package com.walter.spring.ai.ops.util

import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant

fun StringRedisTemplate.listPushIfAbsent(key: String, value: String) {
    val existing = opsForList().range(key, 0, -1) ?: emptyList()
    if (value !in existing) {
        opsForList().rightPush(key, value)
    }
}

fun StringRedisTemplate.zSetPushWithTtl(key: String, value: String, retentionHours: Long) {
    val now = Instant.now()
    val cutoff = now.minusSeconds(retentionHours * 3600).toEpochMilli().toDouble()
    opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff)
    opsForZSet().add(key, value, now.toEpochMilli().toDouble())
}

fun StringRedisTemplate.zSetRangeAllDesc(key: String): List<String> =
    opsForZSet().reverseRange(key, 0, -1)?.toList() ?: emptyList()
