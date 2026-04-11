package com.walter.spring.ai.ops.util

import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant

fun StringRedisTemplate.listPushIfAbsent(key: String, value: String) {
    val existing = opsForList().range(key, 0, -1) ?: emptyList()
    if (value !in existing) {
        opsForList().rightPush(key, value)
    }
}

fun StringRedisTemplate.listPushWithTtl(key: String, value: String, retentionHours: Long) {
    val now = Instant.now()
    val cutoff = now.minusSeconds(retentionHours * 3600)

    val existing = opsForList().range(key, 0, -1) ?: emptyList()

    existing.forEach { element ->
        val insertedAt = element.toInsertedAt() ?: return@forEach
        if (insertedAt.isBefore(cutoff)) {
            opsForList().remove(key, 0, element)
        }
    }

    opsForList().rightPush(key, "${value}::${now.toEpochMilli()}")
}

private fun String.toInsertedAt(): Instant? {
    val epochMillis = substringAfterLast("::").toLongOrNull() ?: return null
    return Instant.ofEpochMilli(epochMillis)
}
