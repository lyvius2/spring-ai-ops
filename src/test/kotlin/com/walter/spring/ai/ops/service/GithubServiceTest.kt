package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.connector.GithubConnector
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.GithubDifferInquiry
import com.walter.spring.ai.ops.connector.dto.GithubFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.verify
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

@ExtendWith(MockitoExtension::class)
class GithubServiceTest {

    @Mock
    private lateinit var redisTemplate: StringRedisTemplate

    @Mock
    private lateinit var githubConnector: GithubConnector

    @Mock
    private lateinit var valueOperations: ValueOperations<String, String>

    // ── getGithubToken ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 토큰이 있으면 Redis 값 반환")
    fun givenTokenInRedis_whenGetGithubToken_thenReturnsRedisToken() {
        // given
        val service = GithubService(redisTemplate, githubConnector, "")
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
        val service = GithubService(redisTemplate, githubConnector, "config-token")
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
        val service = GithubService(redisTemplate, githubConnector, "")
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
        val service = GithubService(redisTemplate, githubConnector, "")
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
        val service = GithubService(redisTemplate, githubConnector, "")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(GithubService.GITHUB_TOKEN_KEY)).willReturn("some-token")

        // when
        val result = service.isTokenConfigured()

        // then
        assertThat(result).isTrue()
    }

    // ── executeInquiryDiffer ──────────────────────────────────────────────────

    @Test
    @DisplayName("GithubConnector compare 결과를 그대로 반환")
    fun givenValidInquiry_whenExecuteInquiryDiffer_thenReturnsCompareResult() {
        // given
        val service = GithubService(redisTemplate, githubConnector, "")
        val inquiry = GithubDifferInquiry("owner", "repo", "base-sha", "head-sha")
        val expected = GithubCompareResult(files = listOf(GithubFile(filename = "src/Main.kt", status = "modified")))
        given(githubConnector.compare("owner", "repo", "base-sha...head-sha")).willReturn(expected)

        // when
        val result = service.executeInquiryDiffer(inquiry)

        // then
        assertThat(result).isEqualTo(expected)
        assertThat(result.files).hasSize(1)
        assertThat(result.files[0].filename).isEqualTo("src/Main.kt")
    }

    @Test
    @DisplayName("GithubConnector가 오류를 반환하면 errorMessage가 있는 결과 반환")
    fun givenConnectorError_whenExecuteInquiryDiffer_thenReturnsErrorResult() {
        // given
        val service = GithubService(redisTemplate, githubConnector, "")
        val inquiry = GithubDifferInquiry("owner", "repo", "base-sha", "head-sha")
        val errorResult = GithubCompareResult(errorMessage = "Not Found")
        given(githubConnector.compare("owner", "repo", "base-sha...head-sha")).willReturn(errorResult)

        // when
        val result = service.executeInquiryDiffer(inquiry)

        // then
        assertThat(result.errorMessage).isEqualTo("Not Found")
        assertThat(result.files).isEmpty()
    }

    @Test
    @DisplayName("compare 호출 시 base...head 형식으로 basehead 구성")
    fun givenInquiry_whenExecuteInquiryDiffer_thenCallsCompareWithCorrectBasehead() {
        // given
        val service = GithubService(redisTemplate, githubConnector, "")
        val inquiry = GithubDifferInquiry("walter", "my-repo", "abc123", "def456")
        given(githubConnector.compare("walter", "my-repo", "abc123...def456")).willReturn(GithubCompareResult())

        // when
        service.executeInquiryDiffer(inquiry)

        // then
        verify(githubConnector).compare("walter", "my-repo", "abc123...def456")
    }

    @Test
    @DisplayName("토큰이 없으면 isTokenConfigured가 false 반환")
    fun givenNoToken_whenIsTokenConfigured_thenReturnsFalse() {
        // given
        val service = GithubService(redisTemplate, githubConnector, "")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(GithubService.GITHUB_TOKEN_KEY)).willReturn(null)

        // when
        val result = service.isTokenConfigured()

        // then
        assertThat(result).isFalse()
    }
}
