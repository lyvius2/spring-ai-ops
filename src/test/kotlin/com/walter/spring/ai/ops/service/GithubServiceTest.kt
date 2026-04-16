package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITHUB_URL
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GIT_REMOTE_TOKEN
import com.walter.spring.ai.ops.connector.GithubConnector
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.GithubDifferInquiry
import com.walter.spring.ai.ops.connector.dto.GithubFile
import com.walter.spring.ai.ops.record.CodeReviewRecord
import com.walter.spring.ai.ops.util.CryptoProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.verify
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.data.redis.core.ListOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GithubServiceTest {

    @Mock private lateinit var redisTemplate: StringRedisTemplate
    @Mock private lateinit var githubConnector: GithubConnector
    @Mock private lateinit var objectMapper: ObjectMapper
    @Mock private lateinit var cryptoProvider: CryptoProvider
    @Mock private lateinit var valueOperations: ValueOperations<String, String>
    @Mock private lateinit var listOperations: ListOperations<String, String>

    @BeforeEach
    fun setUp() {
        given(cryptoProvider.encrypt(anyString())).willAnswer { it.getArgument(0) }
        given(cryptoProvider.decrypt(anyString())).willAnswer { it.getArgument(0) }
    }

    private fun buildService(
        configuredToken: String = "",
        githubUrlFromConfig: String = "https://api.github.com",
    ) = GithubService(redisTemplate, githubConnector, objectMapper, cryptoProvider, 120L, 5L, configuredToken, githubUrlFromConfig)

    // ── getGithubToken ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 토큰이 있으면 Redis 값 반환")
    fun givenTokenInRedis_whenGetGithubToken_thenReturnsRedisToken() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GIT_REMOTE_TOKEN)).willReturn("redis-token")

        // when
        val result = service.getGithubToken()

        // then
        assertThat(result).isEqualTo("redis-token")
    }

    @Test
    @DisplayName("Redis에 토큰이 없고 설정값이 있으면 설정값 반환")
    fun givenNoRedisTokenAndConfigToken_whenGetGithubToken_thenReturnsConfigToken() {
        // given
        val service = buildService(configuredToken = "config-token")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GIT_REMOTE_TOKEN)).willReturn(null)

        // when
        val result = service.getGithubToken()

        // then
        assertThat(result).isEqualTo("config-token")
    }

    @Test
    @DisplayName("Redis와 설정값 모두 없으면 null 반환")
    fun givenNoToken_whenGetGithubToken_thenReturnsNull() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GIT_REMOTE_TOKEN)).willReturn(null)

        // when
        val result = service.getGithubToken()

        // then
        assertThat(result).isNull()
    }

    // ── setGithubToken ────────────────────────────────────────────────────────

    @Test
    @DisplayName("setGithubToken 호출 시 Redis에 저장")
    fun givenValidToken_whenSetGithubToken_thenSavesToRedis() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)

        // when
        service.setGithubToken("my-token")

        // then
        verify(valueOperations).set(REDIS_KEY_GIT_REMOTE_TOKEN, "my-token")
    }

    // ── isTokenConfigured ─────────────────────────────────────────────────────

    @Test
    @DisplayName("토큰이 있으면 isTokenConfigured가 true 반환")
    fun givenToken_whenIsTokenConfigured_thenReturnsTrue() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GIT_REMOTE_TOKEN)).willReturn("some-token")

        // when
        val result = service.isTokenConfigured()

        // then
        assertThat(result).isTrue()
    }

    @Test
    @DisplayName("토큰이 없으면 isTokenConfigured가 false 반환")
    fun givenNoToken_whenIsTokenConfigured_thenReturnsFalse() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GIT_REMOTE_TOKEN)).willReturn(null)

        // when
        val result = service.isTokenConfigured()

        // then
        assertThat(result).isFalse()
    }

    // ── getGithubUrl ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 URL이 있으면 Redis 값 반환 (Redis 우선)")
    fun givenUrlInRedis_whenGetGithubUrl_thenReturnsRedisUrl() {
        // given
        val service = buildService(githubUrlFromConfig = "https://api.github.com")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_URL)).willReturn("https://github.example.com")

        // when
        val result = service.getGithubUrl()

        // then
        assertThat(result).isEqualTo("https://github.example.com")
    }

    @Test
    @DisplayName("Redis에 URL이 없으면 property 설정값 반환")
    fun givenNoRedisUrl_whenGetGithubUrl_thenReturnsConfigUrl() {
        // given
        val service = buildService(githubUrlFromConfig = "https://api.github.com")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_URL)).willReturn(null)

        // when
        val result = service.getGithubUrl()

        // then
        assertThat(result).isEqualTo("https://api.github.com")
    }

    @Test
    @DisplayName("Redis와 property 둘 다 값이 있으면 Redis 값이 우선")
    fun givenBothRedisAndConfigUrl_whenGetGithubUrl_thenRedisHasPriority() {
        // given
        val service = buildService(githubUrlFromConfig = "https://api.github.com")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_URL)).willReturn("https://github.enterprise.com")

        // when
        val result = service.getGithubUrl()

        // then
        assertThat(result).isEqualTo("https://github.enterprise.com")
    }

    // ── setGithubUrl ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("setGithubUrl 호출 시 Redis에 저장")
    fun givenValidUrl_whenSetGithubUrl_thenSavesToRedis() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)

        // when
        service.setGithubUrl("https://github.enterprise.com")

        // then
        verify(valueOperations).set(REDIS_KEY_GITHUB_URL, "https://github.enterprise.com")
    }

    // ── executeInquiryDiffer ──────────────────────────────────────────────────

    @Test
    @DisplayName("before가 일반 SHA이면 compare API 호출")
    fun givenNormalBase_whenExecuteInquiryDiffer_thenCallsCompare() {
        // given
        val service = buildService()
        val inquiry = GithubDifferInquiry("owner", "repo", "base-sha", "head-sha")
        val expected = GithubCompareResult(files = listOf(GithubFile(filename = "src/Main.kt", status = "modified")))
        given(githubConnector.compare("owner", "repo", "base-sha...head-sha")).willReturn(expected)

        // when
        val result = service.executeInquiryDiffer(inquiry)

        // then
        verify(githubConnector).compare("owner", "repo", "base-sha...head-sha")
        assertThat(result).isEqualTo(expected)
    }

    @Test
    @DisplayName("before가 전부 0이면 getCommit API 호출 (신규 브랜치 첫 push)")
    fun givenEmptyShaBase_whenExecuteInquiryDiffer_thenCallsGetCommit() {
        // given
        val service = buildService()
        val inquiry = GithubDifferInquiry("owner", "repo", GithubService.EMPTY_SHA, "head-sha")
        val expected = GithubCompareResult(files = listOf(GithubFile(filename = "README.md", status = "added")))
        given(githubConnector.getCommit("owner", "repo", "head-sha")).willReturn(expected)

        // when
        val result = service.executeInquiryDiffer(inquiry)

        // then
        verify(githubConnector).getCommit("owner", "repo", "head-sha")
        assertThat(result).isEqualTo(expected)
    }

    @Test
    @DisplayName("compare 결과 files가 비어있으면 getCommit으로 fallback (250 커밋 제한)")
    fun givenCompareReturnsEmptyFiles_whenExecuteInquiryDiffer_thenFallsBackToGetCommit() {
        // given
        val service = buildService()
        val inquiry = GithubDifferInquiry("owner", "repo", "base-sha", "head-sha")
        val emptyCompareResult = GithubCompareResult(files = emptyList())
        val fallbackResult = GithubCompareResult(files = listOf(GithubFile(filename = "src/Main.kt", status = "modified")))
        given(githubConnector.compare("owner", "repo", "base-sha...head-sha")).willReturn(emptyCompareResult)
        given(githubConnector.getCommit("owner", "repo", "head-sha")).willReturn(fallbackResult)

        // when
        val result = service.executeInquiryDiffer(inquiry)

        // then
        verify(githubConnector).compare("owner", "repo", "base-sha...head-sha")
        verify(githubConnector).getCommit("owner", "repo", "head-sha")
        assertThat(result.files).hasSize(1)
        assertThat(result.files[0].filename).isEqualTo("src/Main.kt")
    }

    @Test
    @DisplayName("compare 결과 files가 비어있어도 errorMessage가 있으면 fallback 하지 않음")
    fun givenCompareReturnsErrorMessage_whenExecuteInquiryDiffer_thenDoesNotFallBack() {
        // given
        val service = buildService()
        val inquiry = GithubDifferInquiry("owner", "repo", "base-sha", "head-sha")
        val errorResult = GithubCompareResult(files = emptyList(), errorMessage = "Failed to connect to GitHub API.")
        given(githubConnector.compare("owner", "repo", "base-sha...head-sha")).willReturn(errorResult)

        // when
        val result = service.executeInquiryDiffer(inquiry)

        // then
        verify(githubConnector).compare("owner", "repo", "base-sha...head-sha")
        assertThat(result.errorMessage).isNotBlank()
        assertThat(result.files).isEmpty()
    }

    @Test
    @DisplayName("compare 호출 시 base...head 형식으로 basehead 구성")
    fun givenInquiry_whenExecuteInquiryDiffer_thenCallsCompareWithCorrectBasehead() {
        // given
        val service = buildService()
        val inquiry = GithubDifferInquiry("walter", "my-repo", "abc123", "def456")
        given(githubConnector.compare("walter", "my-repo", "abc123...def456")).willReturn(GithubCompareResult())

        // when
        service.executeInquiryDiffer(inquiry)

        // then
        verify(githubConnector).compare("walter", "my-repo", "abc123...def456")
    }

    // ── saveCodeReviewRecord ──────────────────────────────────────────────────

    @Test
    @DisplayName("saveCodeReviewRecord 호출 시 Redis list에 저장")
    fun givenValidRecord_whenSaveCodeReviewRecord_thenPushesToRedisList() {
        // given
        val service = buildService()
        val record = CodeReviewRecord(LocalDateTime.now(), "my-app", "https://github.com/owner/repo/commit/abc", "feat: add feature", emptyList(), "## Review", LocalDateTime.now(), emptyList())
        given(redisTemplate.opsForList()).willReturn(listOperations)
        given(listOperations.range("commit:my-app", 0, -1)).willReturn(emptyList())
        given(objectMapper.writeValueAsString(record)).willReturn("""{"application":"my-app"}""")

        // when
        service.saveCodeReviewRecord(record)

        // then
        verify(listOperations).rightPush(org.mockito.ArgumentMatchers.eq("commit:my-app"), org.mockito.ArgumentMatchers.anyString())
    }

    // ── getCodeReviewRecords ──────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 레코드가 있으면 역직렬화된 목록 반환")
    fun givenRecordsInRedis_whenGetCodeReviewRecords_thenReturnsDeserializedList() {
        // given
        val service = buildService()
        val json = """{"application":"my-app"}"""
        val record = CodeReviewRecord(LocalDateTime.now(), "my-app", "https://github.com", "commit msg", emptyList(), "review", LocalDateTime.now(), emptyList())
        given(redisTemplate.opsForList()).willReturn(listOperations)
        given(listOperations.range("commit:my-app", 0, -1)).willReturn(listOf(json))
        given(objectMapper.readValue(json, CodeReviewRecord::class.java)).willReturn(record)

        // when
        val result = service.getCodeReviewRecords("my-app")

        // then
        assertThat(result).hasSize(1)
        assertThat(result[0].application).isEqualTo("my-app")
    }

    @Test
    @DisplayName("Redis가 null을 반환하면 빈 목록 반환")
    fun givenNullFromRedis_whenGetCodeReviewRecords_thenReturnsEmptyList() {
        // given
        val service = buildService()
        given(redisTemplate.opsForList()).willReturn(listOperations)
        given(listOperations.range("commit:my-app", 0, -1)).willReturn(null)

        // when
        val result = service.getCodeReviewRecords("my-app")

        // then
        assertThat(result).isEmpty()
    }
}
