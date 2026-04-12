package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.connector.GithubConnector
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.GithubDifferInquiry
import com.walter.spring.ai.ops.connector.dto.GithubFile
import com.walter.spring.ai.ops.record.CodeReviewRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.verify
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.ListOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class GithubServiceTest {

    @Mock private lateinit var redisTemplate: StringRedisTemplate
    @Mock private lateinit var githubConnector: GithubConnector
    @Mock private lateinit var objectMapper: ObjectMapper
    @Mock private lateinit var valueOperations: ValueOperations<String, String>
    @Mock private lateinit var listOperations: ListOperations<String, String>

    private fun buildService(configuredToken: String = "") =
        GithubService(redisTemplate, githubConnector, objectMapper, 120L, 5L, configuredToken)

    // ── getGithubToken ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 토큰이 있으면 Redis 값 반환")
    fun givenTokenInRedis_whenGetGithubToken_thenReturnsRedisToken() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(GithubService.GITHUB_TOKEN_KEY)).willReturn("redis-token")

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
        given(valueOperations.get(GithubService.GITHUB_TOKEN_KEY)).willReturn(null)

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
        given(valueOperations.get(GithubService.GITHUB_TOKEN_KEY)).willReturn(null)

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
        verify(valueOperations).set(GithubService.GITHUB_TOKEN_KEY, "my-token")
    }

    // ── isTokenConfigured ─────────────────────────────────────────────────────

    @Test
    @DisplayName("토큰이 있으면 isTokenConfigured가 true 반환")
    fun givenToken_whenIsTokenConfigured_thenReturnsTrue() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(GithubService.GITHUB_TOKEN_KEY)).willReturn("some-token")

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
        given(valueOperations.get(GithubService.GITHUB_TOKEN_KEY)).willReturn(null)

        // when
        val result = service.isTokenConfigured()

        // then
        assertThat(result).isFalse()
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
        val record = CodeReviewRecord(LocalDateTime.now(), "my-app", "https://github.com/owner/repo/commit/abc", "feat: add feature", "## Review", LocalDateTime.now())
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
        val record = CodeReviewRecord(LocalDateTime.now(), "my-app", "https://github.com", "commit msg", "review", LocalDateTime.now())
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
