package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_APP_GIT
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_APPLICATIONS
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

@ExtendWith(MockitoExtension::class)
class ApplicationServiceTest {

    @Mock
    private lateinit var redisTemplate: StringRedisTemplate

    @Mock
    private lateinit var setOperations: SetOperations<String, String>

    @Mock
    private lateinit var valueOperations: ValueOperations<String, String>

    // ── getApps ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 앱 목록이 있는 경우 해당 목록 반환")
    fun getApps_returnsList_whenRedisHasApps() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        `when`(setOperations.members(REDIS_KEY_APPLICATIONS)).thenReturn(setOf("app1", "app2"))
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
    @DisplayName("처음 등록되는 앱 이름인 경우 Redis에 추가 (gitUrl 없음)")
    fun addApp_addsToRedis_whenListIsEmpty() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate)

        // when
        applicationService.addApp("app1")

        // then
        verify(setOperations).add(REDIS_KEY_APPLICATIONS, "app1")
    }

    @Test
    @DisplayName("중복되지 않은 앱 이름인 경우 Redis에 추가 (gitUrl 없음)")
    fun addApp_addsToRedis_whenAppIsNew() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate)

        // when
        applicationService.addApp("app2")

        // then
        verify(setOperations).add(REDIS_KEY_APPLICATIONS, "app2")
    }

    @Test
    @DisplayName("이미 등록된 앱 이름인 경우 예외 없이 무시됨 (Set은 중복 자동 무시)")
    fun addApp_completesWithoutException_whenAppAlreadyExists() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate)

        // when & then (예외 발생 시 테스트 실패)
        applicationService.addApp("app1")
    }

    @Test
    @DisplayName("이미 등록된 앱 이름인 경우에도 Set.add는 호출됨 (중복 여부는 Redis가 판단)")
    fun addApp_callsSetAdd_evenWhenAppAlreadyExists() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate)

        // when
        applicationService.addApp("app1")

        // then — Set.add 호출은 항상 발생하고, 중복 여부는 Redis가 처리
        verify(setOperations).add(REDIS_KEY_APPLICATIONS, "app1")
    }

    @Test
    @DisplayName("gitUrl이 있는 경우 Redis Value에 저장")
    fun addApp_savesGitUrl_whenGitUrlProvided() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        val applicationService = ApplicationService(redisTemplate)

        // when
        applicationService.addApp("app1", "http://github.com/owner/repo.git")

        // then
        verify(valueOperations).set("${REDIS_KEY_APP_GIT}app1", "http://github.com/owner/repo.git")
    }

    @Test
    @DisplayName("gitUrl이 SSH 프로토콜인 경우 예외 발생")
    fun addApp_throwsException_whenGitUrlIsNotHttp() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate)

        // when & then
        assertThrows<IllegalArgumentException> {
            applicationService.addApp("app1", "git@github.com:owner/repo.git")
        }
    }

    @Test
    @DisplayName("gitUrl이 null인 경우 기존 git URL을 덮어쓰지 않음 (saveGitUrl 미호출)")
    fun givenGitUrlIsNull_whenAddApp_thenExistingGitUrlIsPreserved() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate)

        // when
        applicationService.addApp("app1", null)

        // then — saveGitUrl이 호출되지 않으므로 git 키에 대한 delete/set 없음
        verify(redisTemplate, never()).delete("${REDIS_KEY_APP_GIT}app1")
        verify(redisTemplate, never()).opsForValue()
    }

    // ── removeApp ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("앱 이름으로 Redis에서 삭제하고 git 키도 함께 삭제")
    fun removeApp_removesFromRedisAndDeletesGitKey() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate)

        // when
        applicationService.removeApp("app1")

        // then
        verify(setOperations).remove(REDIS_KEY_APPLICATIONS, "app1")
        verify(redisTemplate).delete("${REDIS_KEY_APP_GIT}app1")
    }

    // ── getGitUrl ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 git URL이 있는 경우 반환")
    fun getGitUrl_returnsUrl_whenRedisHasValue() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get("${REDIS_KEY_APP_GIT}app1")).thenReturn("http://github.com/owner/repo.git")
        val applicationService = ApplicationService(redisTemplate)

        // when
        val result = applicationService.getGitUrl("app1")

        // then
        assertThat(result).isEqualTo("http://github.com/owner/repo.git")
    }

    @Test
    @DisplayName("Redis에 git URL이 없는 경우 null 반환")
    fun getGitUrl_returnsNull_whenRedisHasNoValue() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get("${REDIS_KEY_APP_GIT}app1")).thenReturn(null)
        val applicationService = ApplicationService(redisTemplate)

        // when
        val result = applicationService.getGitUrl("app1")

        // then
        assertThat(result).isNull()
    }

    // ── getGitRepositoryByAppName ─────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 git URL이 있는 경우 해당 URL 반환")
    fun getGitRepositoryByAppName_returnsUrl_whenRedisHasValue() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get("${REDIS_KEY_APP_GIT}app1")).thenReturn("http://github.com/owner/repo.git")
        val applicationService = ApplicationService(redisTemplate)

        // when
        val result = applicationService.getGitRepoByAppName("app1")

        // then
        assertThat(result).isEqualTo("http://github.com/owner/repo.git")
    }

    @Test
    @DisplayName("Redis에 git URL이 없는 경우 IllegalStateException 발생")
    fun getGitRepositoryByAppName_throwsIllegalStateException_whenRedisHasNoValue() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get("${REDIS_KEY_APP_GIT}app1")).thenReturn(null)
        val applicationService = ApplicationService(redisTemplate)

        // when & then
        assertThrows<IllegalStateException> {
            applicationService.getGitRepoByAppName("app1")
        }
    }

    // ── updateApp ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("앱 이름이 변경되는 경우 기존 이름 삭제 후 새 이름으로 추가 (gitUrl null)")
    fun updateApp_renamesApp_whenNameChanged() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate)

        // when
        applicationService.updateApp("oldApp", "newApp", null)

        // then
        verify(setOperations).remove(REDIS_KEY_APPLICATIONS, "oldApp")
        verify(setOperations).add(REDIS_KEY_APPLICATIONS, "newApp")
        verify(redisTemplate).delete("${REDIS_KEY_APP_GIT}oldApp")
    }

    @Test
    @DisplayName("앱 이름이 동일한 경우 Set 변경 없이 git URL만 업데이트")
    fun updateApp_updatesGitUrlOnly_whenNameUnchanged() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        val applicationService = ApplicationService(redisTemplate)

        // when
        applicationService.updateApp("app1", "app1", "http://github.com/owner/repo.git")

        // then
        verify(redisTemplate, never()).opsForSet()
        verify(valueOperations).set("${REDIS_KEY_APP_GIT}app1", "http://github.com/owner/repo.git")
    }

    @Test
    @DisplayName("updateApp에서 SSH gitUrl인 경우 예외 발생")
    fun updateApp_throwsException_whenGitUrlIsNotHttp() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate)

        // when & then
        assertThrows<IllegalArgumentException> {
            applicationService.updateApp("oldApp", "newApp", "git@github.com:owner/repo.git")
        }
    }

    @Test
    @DisplayName("이름이 동일하고 gitUrl이 null인 경우 기존 git URL을 덮어쓰지 않음")
    fun givenSameNameAndGitUrlNull_whenUpdateApp_thenExistingGitUrlIsPreserved() {
        // given
        val applicationService = ApplicationService(redisTemplate)

        // when
        applicationService.updateApp("app1", "app1", null)

        // then — 이름이 같으므로 Set 변경 없고, gitUrl이 null이므로 saveGitUrl 미호출
        verify(redisTemplate, never()).opsForSet()
        verify(redisTemplate, never()).opsForValue()
        verify(redisTemplate, never()).delete(anyString())
    }

    @Test
    @DisplayName("이름이 변경되고 gitUrl이 null인 경우 새 앱의 git URL을 삭제하지 않음")
    fun givenNameChangedAndGitUrlNull_whenUpdateApp_thenNewAppGitUrlIsPreserved() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate)

        // when
        applicationService.updateApp("oldApp", "newApp", null)

        // then — 이름 변경으로 oldApp git 키는 삭제되지만, newApp git 키는 건드리지 않음
        verify(redisTemplate).delete("${REDIS_KEY_APP_GIT}oldApp")
        verify(redisTemplate, never()).delete("${REDIS_KEY_APP_GIT}newApp")
        verify(redisTemplate, never()).opsForValue()
    }
}
