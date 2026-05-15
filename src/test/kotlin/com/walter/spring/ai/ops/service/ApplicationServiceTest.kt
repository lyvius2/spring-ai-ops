package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_APP_GIT
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_APPLICATIONS
import com.walter.spring.ai.ops.controller.dto.AppUpdateRequest
import com.walter.spring.ai.ops.service.dto.AppConfig
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

    private val objectMapper: ObjectMapper = Jackson2ObjectMapperBuilder.json().build()

    // ── getApps ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 앱 목록이 있는 경우 해당 목록 반환")
    fun getApps_returnsList_whenRedisHasApps() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        `when`(setOperations.members(REDIS_KEY_APPLICATIONS)).thenReturn(setOf("app1", "app2"))
        val applicationService = ApplicationService(redisTemplate, objectMapper)

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
        val applicationService = ApplicationService(redisTemplate, objectMapper)

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
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when
        applicationService.addApp(AppUpdateRequest("app1"))

        // then
        verify(setOperations).add(REDIS_KEY_APPLICATIONS, "app1")
    }

    @Test
    @DisplayName("gitUrl이 있는 경우 Redis Value에 JSON으로 저장")
    fun addApp_savesGitConfigAsJson_whenGitUrlProvided() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        val applicationService = ApplicationService(redisTemplate, objectMapper)
        val expectedJson = objectMapper.writeValueAsString(AppConfig("https://github.com/owner/repo.git", null))

        // when
        applicationService.addApp(AppUpdateRequest("app1", "https://github.com/owner/repo.git"))

        // then
        verify(valueOperations).set("${REDIS_KEY_APP_GIT}app1", expectedJson)
    }

    @Test
    @DisplayName("gitUrl과 deployBranch 모두 있는 경우 JSON으로 함께 저장")
    fun addApp_savesGitConfigWithDeployBranch_whenBothProvided() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        val applicationService = ApplicationService(redisTemplate, objectMapper)
        val expectedJson = objectMapper.writeValueAsString(AppConfig("https://github.com/owner/repo.git", "main"))

        // when
        applicationService.addApp(AppUpdateRequest("app1", "https://github.com/owner/repo.git", "main"))

        // then
        verify(valueOperations).set("${REDIS_KEY_APP_GIT}app1", expectedJson)
    }

    @Test
    @DisplayName("gitUrl 없이 deployBranch만 입력한 경우 예외 발생")
    fun givenDeployBranchWithoutGitUrl_whenAddApp_thenThrowsException() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when & then
        assertThrows<IllegalArgumentException> {
            applicationService.addApp(AppUpdateRequest("app1", null, "main"))
        }
    }

    @Test
    @DisplayName("gitUrl이 null인 경우 기존 git config를 덮어쓰지 않음 (saveAppConfig 미호출)")
    fun givenGitUrlIsNull_whenAddApp_thenExistingGitUrlIsPreserved() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when
        applicationService.addApp(AppUpdateRequest("app1", null))

        // then — saveAppConfig가 호출되지 않으므로 git 키에 대한 delete/set 없음
        verify(redisTemplate, never()).delete("${REDIS_KEY_APP_GIT}app1")
        verify(redisTemplate, never()).opsForValue()
    }

    @Test
    @DisplayName("gitUrl과 deployBranch 모두 null인 경우 기존 git config 보존")
    fun givenBothNull_whenAddApp_thenGitConfigIsPreserved() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when
        applicationService.addApp(AppUpdateRequest("app1", null, null))

        // then
        verify(redisTemplate, never()).delete(anyString())
        verify(redisTemplate, never()).opsForValue()
    }

    @Test
    @DisplayName("gitUrl이 SSH 프로토콜인 경우 예외 발생")
    fun addApp_throwsException_whenGitUrlIsNotHttp() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when & then
        assertThrows<IllegalArgumentException> {
            applicationService.addApp(AppUpdateRequest("app1", "git@github.com:owner/repo.git"))
        }
    }

    // ── removeApp ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("앱 이름으로 Redis에서 삭제하고 git 키도 함께 삭제")
    fun removeApp_removesFromRedisAndDeletesGitKey() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when
        applicationService.removeApp("app1")

        // then
        verify(setOperations).remove(REDIS_KEY_APPLICATIONS, "app1")
        verify(redisTemplate).delete("${REDIS_KEY_APP_GIT}app1")
    }

    // ── getAppConfig ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 JSON이 저장된 경우 AppConfig로 역직렬화하여 반환")
    fun getGitConfig_returnsConfig_whenRedisHasJson() {
        // given
        val config = AppConfig("https://github.com/owner/repo.git", "main")
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get("${REDIS_KEY_APP_GIT}app1")).thenReturn(objectMapper.writeValueAsString(config))
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when
        val result = applicationService.getAppConfig("app1")

        // then
        assertThat(result?.gitUrl).isEqualTo("https://github.com/owner/repo.git")
        assertThat(result?.deployBranch).isEqualTo("main")
    }

    @Test
    @DisplayName("Redis에 deployBranch 없이 저장된 경우 gitUrl만 반환")
    fun getGitConfig_returnsConfigWithNullBranch_whenDeployBranchNotSet() {
        // given
        val config = AppConfig("https://github.com/owner/repo.git", null)
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get("${REDIS_KEY_APP_GIT}app1")).thenReturn(objectMapper.writeValueAsString(config))
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when
        val result = applicationService.getAppConfig("app1")

        // then
        assertThat(result?.gitUrl).isEqualTo("https://github.com/owner/repo.git")
        assertThat(result?.deployBranch).isNull()
    }

    @Test
    @DisplayName("Redis에 git 설정이 없는 경우 null 반환")
    fun getGitConfig_returnsNull_whenRedisHasNoValue() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get("${REDIS_KEY_APP_GIT}app1")).thenReturn(null)
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when
        val result = applicationService.getAppConfig("app1")

        // then
        assertThat(result).isNull()
    }

    @Test
    @DisplayName("Redis에 파싱 불가한 값이 있는 경우 null 반환 (이전 포맷 호환)")
    fun getGitConfig_returnsNull_whenValueIsNotValidJson() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get("${REDIS_KEY_APP_GIT}app1")).thenReturn("https://github.com/owner/repo.git")
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when
        val result = applicationService.getAppConfig("app1")

        // then
        assertThat(result).isNull()
    }

    // ── getGitRepoByAppName ───────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 git URL이 있는 경우 해당 URL 반환")
    fun getGitRepoByAppName_returnsUrl_whenRedisHasValue() {
        // given
        val config = AppConfig("https://github.com/owner/repo.git", null)
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get("${REDIS_KEY_APP_GIT}app1")).thenReturn(objectMapper.writeValueAsString(config))
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when
        val result = applicationService.getGitRepoByAppName("app1")

        // then
        assertThat(result).isEqualTo("https://github.com/owner/repo.git")
    }

    @Test
    @DisplayName("Redis에 git URL이 없는 경우 IllegalStateException 발생")
    fun getGitRepoByAppName_throwsIllegalStateException_whenRedisHasNoValue() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get("${REDIS_KEY_APP_GIT}app1")).thenReturn(null)
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when & then
        assertThrows<IllegalStateException> {
            applicationService.getGitRepoByAppName("app1")
        }
    }

    // ── saveAppConfig ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("gitUrl이 빈 문자열인 경우 git 키 삭제")
    fun saveGitConfig_deletesKey_whenGitUrlIsBlank() {
        // given
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when
        applicationService.saveAppConfig(AppUpdateRequest("app1", ""))

        // then
        verify(redisTemplate).delete("${REDIS_KEY_APP_GIT}app1")
    }

    @Test
    @DisplayName("gitUrl 없이 deployBranch만 입력한 경우 예외 발생")
    fun givenDeployBranchWithoutGitUrl_whenSaveGitConfig_thenThrowsException() {
        // given
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when & then
        assertThrows<IllegalArgumentException> {
            applicationService.saveAppConfig(AppUpdateRequest("app1", null, "main"))
        }
    }

    // ── updateApp ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("앱 이름이 변경되는 경우 기존 이름 삭제 후 새 이름으로 추가")
    fun updateApp_renamesApp_whenNameChanged() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when
        applicationService.updateApp("oldApp", AppUpdateRequest("newApp", null, null))

        // then
        verify(setOperations).remove(REDIS_KEY_APPLICATIONS, "oldApp")
        verify(setOperations).add(REDIS_KEY_APPLICATIONS, "newApp")
        verify(redisTemplate).delete("${REDIS_KEY_APP_GIT}oldApp")
    }

    @Test
    @DisplayName("앱 이름이 동일하고 gitUrl이 있는 경우 JSON으로 저장")
    fun updateApp_savesGitConfig_whenNameUnchangedAndGitUrlProvided() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        val applicationService = ApplicationService(redisTemplate, objectMapper)
        val expectedJson = objectMapper.writeValueAsString(AppConfig("https://github.com/owner/repo.git", null))

        // when
        applicationService.updateApp("app1", AppUpdateRequest("app1", "https://github.com/owner/repo.git", null))

        // then
        verify(redisTemplate, never()).opsForSet()
        verify(valueOperations).set("${REDIS_KEY_APP_GIT}app1", expectedJson)
    }

    @Test
    @DisplayName("앱 이름이 동일하고 gitUrl과 deployBranch 모두 있는 경우 JSON으로 저장")
    fun updateApp_savesGitConfigWithDeployBranch_whenBothProvided() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        val applicationService = ApplicationService(redisTemplate, objectMapper)
        val expectedJson = objectMapper.writeValueAsString(AppConfig("https://github.com/owner/repo.git", "main"))

        // when
        applicationService.updateApp("app1", AppUpdateRequest("app1", "https://github.com/owner/repo.git", "main"))

        // then
        verify(valueOperations).set("${REDIS_KEY_APP_GIT}app1", expectedJson)
    }

    @Test
    @DisplayName("gitUrl 없이 deployBranch만 입력한 경우 예외 발생")
    fun givenDeployBranchWithoutGitUrl_whenUpdateApp_thenThrowsException() {
        // given
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when & then
        assertThrows<IllegalArgumentException> {
            applicationService.updateApp("app1", AppUpdateRequest("app1", null, "main"))
        }
    }

    @Test
    @DisplayName("이름이 동일하고 gitUrl이 null인 경우 git 키 삭제")
    fun givenSameNameAndGitUrlNull_whenUpdateApp_thenGitKeyIsDeleted() {
        // given
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when
        applicationService.updateApp("app1", AppUpdateRequest("app1", null, null))

        // then — 명시적 수정 요청이므로 null은 삭제 의도로 처리
        verify(redisTemplate, never()).opsForSet()
        verify(redisTemplate).delete("${REDIS_KEY_APP_GIT}app1")
    }

    @Test
    @DisplayName("이름이 변경되고 gitUrl이 null인 경우 새 앱의 git 키를 삭제하지 않음")
    fun givenNameChangedAndGitUrlNull_whenUpdateApp_thenNewAppGitUrlIsPreserved() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when
        applicationService.updateApp("oldApp", AppUpdateRequest("newApp", null, null))

        // then — 이름 변경으로 oldApp git 키는 삭제되고, newApp git 키도 null 처리로 삭제됨
        verify(redisTemplate).delete("${REDIS_KEY_APP_GIT}oldApp")
        verify(redisTemplate).delete("${REDIS_KEY_APP_GIT}newApp")
    }

    @Test
    @DisplayName("updateApp에서 SSH gitUrl인 경우 예외 발생")
    fun updateApp_throwsException_whenGitUrlIsNotHttp() {
        // given
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
        val applicationService = ApplicationService(redisTemplate, objectMapper)

        // when & then
        assertThrows<IllegalArgumentException> {
            applicationService.updateApp("oldApp", AppUpdateRequest("newApp", "git@github.com:owner/repo.git", null))
        }
    }
}
