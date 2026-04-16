package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_APPLICATIONS
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.StringRedisTemplate

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApplicationServiceTest {

    @Mock
    private lateinit var redisTemplate: StringRedisTemplate

    @Mock
    private lateinit var setOperations: SetOperations<String, String>

    // ── getApps ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 앱 목록이 있는 경우 해당 목록 반환")
    fun getApps_returnsList_whenRedisHasApps() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        `when`(setOperations.members(REDIS_KEY_APPLICATIONS)).thenReturn(linkedSetOf("app1", "app2"))
        val applicationService = ApplicationService(redisTemplate)

        // when
        val result = applicationService.getApps()

        // then
        assertThat(result).containsExactlyInAnyOrder("app1", "app2")
    }

    @Test
    @DisplayName("Redis가 null을 반환하는 경우 빈 목록 반환")
    fun getApps_returnsEmptyList_whenRedisReturnsNull() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        `when`(setOperations.members(REDIS_KEY_APPLICATIONS)).thenReturn(null)
        val applicationService = ApplicationService(redisTemplate)

        // when
        val result = applicationService.getApps()

        // then
        assertThat(result).isEmpty()
    }

    // ── addApp ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("앱 이름이 Redis Set에 추가됨")
    fun addApp_addsToRedis() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate)

        // when
        applicationService.addApp("app1")

        // then
        verify(setOperations).add(REDIS_KEY_APPLICATIONS, "app1")
    }

    @Test
    @DisplayName("이미 등록된 앱 이름인 경우 예외 없이 무시됨 (Set 중복 자동 처리)")
    fun addApp_completesWithoutException_whenAppAlreadyExists() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate)

        // when & then (예외 발생 시 테스트 실패)
        applicationService.addApp("app1")
        applicationService.addApp("app1")
    }

    // ── removeApp ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("앱 이름으로 Redis에서 삭제")
    fun removeApp_removesFromRedis() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate)

        // when
        applicationService.removeApp("app1")

        // then
        verify(setOperations).remove(REDIS_KEY_APPLICATIONS, "app1")
    }
}
