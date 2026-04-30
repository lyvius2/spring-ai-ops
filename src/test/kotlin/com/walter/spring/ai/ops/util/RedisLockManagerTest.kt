package com.walter.spring.ai.ops.util

import com.walter.spring.ai.ops.util.dto.RedisLock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript
import java.time.Duration

@Suppress("UNCHECKED_CAST")
private fun <T> anyObject(): T = Mockito.any() as T

@ExtendWith(MockitoExtension::class)
class RedisLockManagerTest {

    @Mock private lateinit var redisTemplate: StringRedisTemplate
    @Mock private lateinit var valueOperations: ValueOperations<String, String>

    private lateinit var redisLockManager: RedisLockManager

    @BeforeEach
    fun setUp() {
        redisLockManager = RedisLockManager(
            redisTemplate = redisTemplate,
            defaultLockTtlMs = 1_000,
            defaultWaitTimeoutMs = 0,
            defaultRetryIntervalMs = 1,
        )
    }

    @Test
    @DisplayName("applicationName으로 repository lock key를 생성한다")
    fun givenApplicationName_whenRepositoryLockKey_thenReturnsRepositoryScopedKey() {
        // given
        val applicationName = "my-service"

        // when
        val lockKey = redisLockManager.repositoryLockKey(applicationName)

        // then
        assertThat(lockKey).isEqualTo("lock:repository:my-service")
    }

    @Test
    @DisplayName("Redis lock key가 비어 있으면 예외가 발생한다")
    fun givenBlankLockKey_whenAcquire_thenThrowsException() {
        // given
        val lockKey = " "

        // when & then
        assertThatThrownBy {
            redisLockManager.acquire(lockKey)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Redis lock key must not be blank.")
    }

    @Test
    @DisplayName("lock이 비어 있으면 token과 TTL로 lock을 획득한다")
    fun givenFreeLock_whenAcquire_thenSetsTokenWithTtl() {
        // given
        val lockKey = "lock:repository:my-service"
        val ttl = Duration.ofMillis(1_000)
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.setIfAbsent(Mockito.eq(lockKey), anyObject(), Mockito.eq(ttl))).thenReturn(true)

        // when
        val lock = redisLockManager.acquire(lockKey, ttl = ttl)

        // then
        val tokenCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(valueOperations).setIfAbsent(Mockito.eq(lockKey), tokenCaptor.capture(), Mockito.eq(ttl))
        assertThat(lock.key).isEqualTo(lockKey)
        assertThat(lock.token).isEqualTo(tokenCaptor.value)
        assertThat(lock.token).isNotBlank()
    }

    @Test
    @DisplayName("timeout 안에 lock을 획득하지 못하면 예외가 발생한다")
    fun givenBusyLock_whenAcquire_thenThrowsException() {
        // given
        val lockKey = "lock:repository:my-service"
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(
            valueOperations.setIfAbsent(
                Mockito.eq(lockKey),
                anyObject(),
                Mockito.eq(Duration.ofMillis(1_000)),
            ),
        ).thenReturn(false)

        // when & then
        assertThatThrownBy {
            redisLockManager.acquire(lockKey, waitTimeout = Duration.ZERO)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Failed to acquire Redis lock 'lock:repository:my-service' within 0ms.")
    }

    @Test
    @DisplayName("release는 token 비교 Lua script로 lock을 해제한다")
    fun givenOwnedLock_whenRelease_thenExecutesTokenBasedUnlockScript() {
        // given
        val lock = RedisLock("lock:repository:my-service", "owner-token")
        `when`(
            redisTemplate.execute(
                anyObject<RedisScript<Long>>(),
                Mockito.eq(listOf(lock.key)),
                Mockito.eq(lock.token),
            ),
        ).thenReturn(1L)

        // when
        val released = redisLockManager.release(lock)

        // then
        assertThat(released).isTrue()
        verify(redisTemplate).execute(
            anyObject<RedisScript<Long>>(),
            Mockito.eq(listOf(lock.key)),
            Mockito.eq(lock.token),
        )
    }

    @Test
    @DisplayName("token이 맞지 않아 Redis script가 0을 반환하면 release는 false를 반환한다")
    fun givenUnownedLock_whenRelease_thenReturnsFalse() {
        // given
        val lock = RedisLock("lock:repository:my-service", "old-token")
        `when`(
            redisTemplate.execute(
                anyObject<RedisScript<Long>>(),
                Mockito.eq(listOf(lock.key)),
                Mockito.eq(lock.token),
            ),
        ).thenReturn(0L)

        // when
        val released = redisLockManager.release(lock)

        // then
        assertThat(released).isFalse()
    }

    @Test
    @DisplayName("withLock은 lock을 획득하고 block 결과를 반환한 뒤 release한다")
    fun givenSuccessfulBlock_whenWithLock_thenReturnsResultAndReleasesLock() {
        // given
        val lockKey = "lock:repository:my-service"
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(
            valueOperations.setIfAbsent(
                Mockito.eq(lockKey),
                anyObject(),
                Mockito.eq(Duration.ofMillis(1_000)),
            ),
        ).thenReturn(true)
        `when`(
            redisTemplate.execute(
                anyObject<RedisScript<Long>>(),
                Mockito.eq(listOf(lockKey)),
                anyObject<String>(),
            ),
        ).thenReturn(1L)

        // when
        val result = redisLockManager.withLock(lockKey) { "locked-result" }

        // then
        assertThat(result).isEqualTo("locked-result")
        verify(redisTemplate).execute(
            anyObject<RedisScript<Long>>(),
            Mockito.eq(listOf(lockKey)),
            anyObject<String>(),
        )
    }

    @Test
    @DisplayName("withLock의 block이 실패해도 release를 수행하고 예외를 다시 던진다")
    fun givenFailingBlock_whenWithLock_thenReleasesLockAndRethrowsException() {
        // given
        val lockKey = "lock:repository:my-service"
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(
            valueOperations.setIfAbsent(
                Mockito.eq(lockKey),
                anyObject(),
                Mockito.eq(Duration.ofMillis(1_000)),
            ),
        ).thenReturn(true)
        `when`(
            redisTemplate.execute(
                anyObject<RedisScript<Long>>(),
                Mockito.eq(listOf(lockKey)),
                anyObject<String>(),
            ),
        ).thenReturn(1L)

        // when & then
        assertThatThrownBy {
            redisLockManager.withLock(lockKey) {
                throw IllegalArgumentException("failed inside lock")
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("failed inside lock")
        verify(redisTemplate).execute(
            anyObject<RedisScript<Long>>(),
            Mockito.eq(listOf(lockKey)),
            anyObject<String>(),
        )
    }
}
