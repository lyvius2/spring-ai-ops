package com.walter.spring.ai.ops.util.extension

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant

private val log = LoggerFactory.getLogger("RedisExtensions")
private val redisObjectMapper = ObjectMapper().findAndRegisterModules()

fun StringRedisTemplate.zSetPushWithTtl(key: String, value: String, retentionHours: Long) {
    val now = Instant.now()
    val cutoff = now.minusSeconds(retentionHours * 3600).toEpochMilli().toDouble()
    runCatching {
        opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff)
        opsForZSet().add(key, value, now.toEpochMilli().toDouble())
    }.getOrElse { e ->
        log.warn("ZSet write failed for key '{}' — deleting stale key and retrying. cause: {}", key, e.message)
        delete(key)
        opsForZSet().add(key, value, now.toEpochMilli().toDouble())
    }
}

fun StringRedisTemplate.zSetRangeAllDesc(key: String): List<String> =
    runCatching {
        opsForZSet().reverseRange(key, 0, -1)?.toList() ?: emptyList()
    }.getOrElse { e ->
        log.warn("ZSet read failed for key '{}' — returning empty list. cause: {}", key, e.message)
        emptyList()
    }

fun <T> StringRedisTemplate.getArrayList(key: String, clazz: Class<T>): ArrayList<T> {
    val collectionType = redisObjectMapper.typeFactory.constructCollectionType(ArrayList::class.java, clazz)
    val values: List<T> = runCatching {
        val rawValue = opsForValue().get(key) ?: return@runCatching emptyList<T>()
        redisObjectMapper.readValue(rawValue, collectionType)
    }.getOrElse { e ->
        log.warn("List read/parse failed for key '{}' and type '{}'. cause: {}", key, clazz.simpleName, e.message)
        emptyList()
    }
    return ArrayList(values)
}
