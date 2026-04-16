package com.walter.spring.ai.ops.util

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant

private val log = LoggerFactory.getLogger("RedisExtensions")

fun StringRedisTemplate.zSetPushWithTtl(key: String, value: String, retentionHours: Long) {
    val now = Instant.now()
    val score = now.toEpochMilli().toDouble()
    val cutoff = now.minusSeconds(retentionHours * 3600).toEpochMilli().toDouble()
    runCatching {
        executePipelined {
            val ops = opsForZSet()
            ops.removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff)
            ops.add(key, value, score)
        }
    }.getOrElse { e ->
        log.warn("ZSet pipeline write failed for key '{}' — deleting stale key and retrying. cause: {}", key, e.message)
        delete(key)
        executePipelined {
            opsForZSet().add(key, value, score)
        }
    }
}

fun StringRedisTemplate.zSetRangeAllDesc(key: String, limit: Long = -1): List<String> {
    val end = if (limit > 0) limit - 1 else -1L
    return runCatching {
        opsForZSet().reverseRange(key, 0, end)?.toList() ?: emptyList()
    }.getOrElse { e ->
        log.warn("ZSet read failed for key '{}' — deleting stale key and returning empty list. cause: {}", key, e.message)
        delete(key)
        emptyList()
    }
}
