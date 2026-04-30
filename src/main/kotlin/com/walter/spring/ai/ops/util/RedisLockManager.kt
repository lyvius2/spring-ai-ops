package com.walter.spring.ai.ops.util

import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_REPOSITORY_LOCK_PREFIX
import com.walter.spring.ai.ops.util.dto.RedisLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

@Component
class RedisLockManager(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${repository.lock.ttl-ms:30000}") private val defaultLockTtlMs: Long = 30_000,
    @Value("\${repository.lock.wait-timeout-ms:15000}") private val defaultWaitTimeoutMs: Long = 15_000,
    @Value("\${repository.lock.retry-interval-ms:1000}") private val defaultRetryIntervalMs: Long = 1_000,
) {
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

    fun repositoryLockKey(applicationName: String): String = "$REDIS_KEY_REPOSITORY_LOCK_PREFIX$applicationName"

    fun acquire(
        lockKey: String,
        ttl: Duration = Duration.ofMillis(defaultLockTtlMs),
        waitTimeout: Duration = Duration.ofMillis(defaultWaitTimeoutMs),
        retryInterval: Duration = Duration.ofMillis(defaultRetryIntervalMs),
    ): RedisLock {
        require(lockKey.isNotBlank()) { "Redis lock key must not be blank." }
        require(!ttl.isNegative && !ttl.isZero) { "Redis lock TTL must be greater than zero." }
        require(!waitTimeout.isNegative) { "Redis lock wait timeout must not be negative." }
        require(!retryInterval.isNegative && !retryInterval.isZero) { "Redis lock retry interval must be greater than zero." }

        val token = UUID.randomUUID().toString()
        val deadline = System.nanoTime() + waitTimeout.toNanos()
        do {
            val acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, token, ttl) == true
            if (acquired) {
                return RedisLock(lockKey, token)
            }
            if (System.nanoTime() >= deadline) {
                break
            }
            Thread.sleep(retryInterval.toMillis())
        } while (true)

        throw IllegalStateException("Failed to acquire Redis lock '$lockKey' within ${waitTimeout.toMillis()}ms.")
    }

    fun release(lock: RedisLock): Boolean {
        val result = redisTemplate.execute(UNLOCK_SCRIPT, listOf(lock.key), lock.token)
        return result == 1L
    }

    fun <T> withLock(
        lockKey: String,
        ttl: Duration = Duration.ofMillis(defaultLockTtlMs),
        waitTimeout: Duration = Duration.ofMillis(defaultWaitTimeoutMs),
        retryInterval: Duration = Duration.ofMillis(defaultRetryIntervalMs),
        block: () -> T,
    ): T {
        val lock = acquire(lockKey, ttl, waitTimeout, retryInterval)
        try {
            return block()
        } finally {
            release(lock)
        }
    }
}
