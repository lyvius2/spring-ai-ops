package com.walter.spring.ai.ops.connector.cache

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Profile("!local")
@Component
class RedisCacheStoreConnector(
    private val redisTemplate: StringRedisTemplate,
) : CacheStorePort {
    companion object {
        private val UNLOCK_SCRIPT = DefaultRedisScript(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """.trimIndent(),
            Long::class.java,
        )
    }

    private val log = LoggerFactory.getLogger(RedisCacheStoreConnector::class.java)

    override fun get(key: String): String? = redisTemplate.opsForValue().get(key)

    override fun set(key: String, value: String) {
        redisTemplate.opsForValue().set(key, value)
    }

    override fun delete(key: String): Boolean = redisTemplate.delete(key)

    override fun getDataSet(key: String): Set<String> = redisTemplate.opsForSet().members(key) ?: emptySet()

    override fun addToSet(key: String, value: String) {
        redisTemplate.opsForSet().add(key, value)
    }

    override fun removeFromSet(key: String, value: String) {
        redisTemplate.opsForSet().remove(key, value)
    }

    override fun addToTimeOrderedSet(key: String, value: String, retentionHours: Long) {
        val now = Instant.now()
        val cutoff = now.minusSeconds(retentionHours * 3600).toEpochMilli().toDouble()
        runCatching {
            redisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff)
            redisTemplate.opsForZSet().add(key, value, now.toEpochMilli().toDouble())
        }.getOrElse { e ->
            log.warn("Time-ordered set write failed for key '{}' — deleting stale key and retrying. cause: {}", key, e.message)
            redisTemplate.delete(key)
            redisTemplate.opsForZSet().add(key, value, now.toEpochMilli().toDouble())
        }
    }

    override fun getTimeOrderedSetDescending(key: String): List<String> =
        runCatching {
            redisTemplate.opsForZSet().reverseRange(key, 0, -1)?.toList() ?: emptyList()
        }.getOrElse { e ->
            log.warn("Time-ordered set read failed for key '{}' — returning empty list. cause: {}", key, e.message)
            emptyList()
        }

    override fun getTimeOrderedSetSize(key: String): Long = redisTemplate.opsForZSet().zCard(key) ?: 0L

    override fun <T> withLock(
        key: String,
        ttl: Duration,
        waitTimeout: Duration,
        retryInterval: Duration,
        block: () -> T,
    ): T {
        require(key.isNotBlank()) { "Cache lock key must not be blank." }
        require(!ttl.isNegative && !ttl.isZero) { "Cache lock TTL must be greater than zero." }
        require(!waitTimeout.isNegative) { "Cache lock wait timeout must not be negative." }
        require(!retryInterval.isNegative && !retryInterval.isZero) { "Cache lock retry interval must be greater than zero." }

        val token = UUID.randomUUID().toString()
        val deadline = System.nanoTime() + waitTimeout.toNanos()
        do {
            if (redisTemplate.opsForValue().setIfAbsent(key, token, ttl) == true) {
                try {
                    return block()
                } finally {
                    redisTemplate.execute(UNLOCK_SCRIPT, listOf(key), token)
                }
            }
            if (System.nanoTime() >= deadline) {
                break
            }
            Thread.sleep(retryInterval.toMillis())
        } while (true)

        throw IllegalStateException("Failed to acquire cache lock '$key' within ${waitTimeout.toMillis()}ms.")
    }
}
