package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITHUB_URL
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITHUB_TOKEN
import com.walter.spring.ai.ops.connector.GithubConnector
import com.walter.spring.ai.ops.code.DiffSide
import com.walter.spring.ai.ops.connector.dto.GitCommentRequest
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.GitDifferInquiry
import com.walter.spring.ai.ops.connector.dto.GithubFile
import com.walter.spring.ai.ops.connector.dto.GithubIssueCommentResponse
import com.walter.spring.ai.ops.connector.dto.GithubReviewComment
import com.walter.spring.ai.ops.connector.dto.GithubReviewRequest
import com.walter.spring.ai.ops.connector.dto.GithubReviewResponse
import com.walter.spring.ai.ops.service.dto.LlmInlineComment
import com.walter.spring.ai.ops.service.dto.LlmInlineReviewResult
import com.walter.spring.ai.ops.record.CodeReviewRecord
import com.walter.spring.ai.ops.util.CryptoProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
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

    @BeforeEach
    fun setUp() {
        given(cryptoProvider.encrypt(anyString())).willAnswer { it.getArgument(0) }
        given(cryptoProvider.decrypt(anyString())).willAnswer { it.getArgument(0) }
    }

    private fun buildService(
        configuredToken: String = "",
        githubUrlFromConfig: String = "https://api.github.com",
    ) = GithubService(redisTemplate, objectMapper, cryptoProvider, githubConnector, 120L, 5L, configuredToken, githubUrlFromConfig)

    // ── getGithubToken ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 토큰이 있으면 Redis 값 반환")
    fun givenTokenInRedis_whenGetToken_thenReturnsRedisToken() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_TOKEN)).willReturn("redis-token")

        // when
        val result = service.getToken()

        // then
        assertThat(result).isEqualTo("redis-token")
    }

    @Test
    @DisplayName("Redis에 토큰이 없고 설정값이 있으면 설정값 반환")
    fun givenNoRedisTokenAndConfigToken_whenGetToken_thenReturnsConfigToken() {
        // given
        val service = buildService(configuredToken = "config-token")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_TOKEN)).willReturn(null)

        // when
        val result = service.getToken()

        // then
        assertThat(result).isEqualTo("config-token")
    }

    @Test
    @DisplayName("Redis와 설정값 모두 없으면 null 반환")
    fun givenNoToken_whenGetToken_thenReturnsNull() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_TOKEN)).willReturn(null)

        // when
        val result = service.getToken()

        // then
        assertThat(result).isNull()
    }

    // ── setGithubToken ────────────────────────────────────────────────────────

    @Test
    @DisplayName("setGithubToken 호출 시 Redis에 저장")
    fun givenValidToken_whenSetToken_thenSavesToRedis() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)

        // when
        service.setToken("my-token")

        // then
        verify(valueOperations).set(REDIS_KEY_GITHUB_TOKEN, "my-token")
    }

    // ── isTokenConfigured ─────────────────────────────────────────────────────

    @Test
    @DisplayName("토큰이 있으면 isTokenConfigured가 true 반환")
    fun givenToken_whenIsTokenConfigured_thenReturnsTrue() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_TOKEN)).willReturn("some-token")

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
        given(valueOperations.get(REDIS_KEY_GITHUB_TOKEN)).willReturn(null)

        // when
        val result = service.isTokenConfigured()

        // then
        assertThat(result).isFalse()
    }

    // ── getGithubUrl ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 URL이 있으면 Redis 값 반환 (Redis 우선)")
    fun givenUrlInRedis_whenGetUrl_thenReturnsRedisUrl() {
        // given
        val service = buildService(githubUrlFromConfig = "https://api.github.com")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_URL)).willReturn("https://github.example.com")

        // when
        val result = service.getUrl()

        // then
        assertThat(result).isEqualTo("https://github.example.com")
    }

    @Test
    @DisplayName("Redis에 URL이 없으면 property 설정값 반환")
    fun givenNoRedisUrl_whenGetUrl_thenReturnsConfigUrl() {
        // given
        val service = buildService(githubUrlFromConfig = "https://api.github.com")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_URL)).willReturn(null)

        // when
        val result = service.getUrl()

        // then
        assertThat(result).isEqualTo("https://api.github.com")
    }

    @Test
    @DisplayName("Redis와 property 둘 다 값이 있으면 Redis 값이 우선")
    fun givenBothRedisAndConfigUrl_whenGetUrl_thenRedisHasPriority() {
        // given
        val service = buildService(githubUrlFromConfig = "https://api.github.com")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_URL)).willReturn("https://github.enterprise.com")

        // when
        val result = service.getUrl()

        // then
        assertThat(result).isEqualTo("https://github.enterprise.com")
    }

    // ── setGithubUrl ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("setGithubUrl 호출 시 Redis에 저장")
    fun givenValidUrl_whenSetUrl_thenSavesToRedis() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)

        // when
        service.setUrl("https://github.enterprise.com")

        // then
        verify(valueOperations).set(REDIS_KEY_GITHUB_URL, "https://github.enterprise.com")
    }

    // ── executeInquiryDiffer ──────────────────────────────────────────────────

    @Test
    @DisplayName("before가 일반 SHA이면 compare API 호출")
    fun givenNormalBase_whenExecuteDiffer_thenCallsCompare() {
        // given
        val service = buildService()
        val inquiry = GitDifferInquiry("owner", "repo", "base-sha", "head-sha")
        val expected = GithubCompareResult(files = listOf(GithubFile(filename = "src/Main.kt", status = "modified")))
        given(githubConnector.compare("owner", "repo", "base-sha...head-sha")).willReturn(expected)

        // when
        val result = service.executeInquiryDiffer(inquiry)

        // then
        verify(githubConnector).compare("owner", "repo", "base-sha...head-sha")
        assertThat(result).isEqualTo(expected)
    }

    @Test
    @DisplayName("before가 전부 0이면 push에 포함된 모든 commit을 조회")
    fun givenEmptyShaBase_whenExecuteDiffer_thenFetchesAllCommits() {
        // given
        val service = buildService()
        val inquiry = GitDifferInquiry("owner", "repo", GitRemoteService.EMPTY_SHA, "head-sha", listOf("first-sha", "head-sha"))
        given(githubConnector.getCommit("owner", "repo", "first-sha")).willReturn(
            GithubCompareResult(files = listOf(GithubFile(filename = "README.md", status = "added"))),
        )
        given(githubConnector.getCommit("owner", "repo", "head-sha")).willReturn(
            GithubCompareResult(files = listOf(GithubFile(filename = "src/Main.kt", status = "added"))),
        )

        // when
        val result = service.executeInquiryDiffer(inquiry)

        // then
        verify(githubConnector).getCommit("owner", "repo", "first-sha")
        verify(githubConnector).getCommit("owner", "repo", "head-sha")
        assertThat((result as GithubCompareResult).files.map { it.filename }).containsExactly("README.md", "src/Main.kt")
    }

    @Test
    @DisplayName("push commit이 여러 개이면 모든 commit을 조회")
    fun givenMultiplePushedCommits_whenExecuteDiffer_thenFetchesAllCommits() {
        // given
        val service = buildService()
        val inquiry = GitDifferInquiry("owner", "repo", "base-sha", "head-sha", listOf("first-sha", "head-sha"))
        given(githubConnector.getCommit("owner", "repo", "first-sha")).willReturn(
            GithubCompareResult(files = listOf(GithubFile(filename = "README.md", status = "added"))),
        )
        given(githubConnector.getCommit("owner", "repo", "head-sha")).willReturn(
            GithubCompareResult(files = listOf(GithubFile(filename = "src/Main.kt", status = "modified"))),
        )

        // when
        val result = service.executeInquiryDiffer(inquiry)

        // then
        verify(githubConnector).getCommit("owner", "repo", "first-sha")
        verify(githubConnector).getCommit("owner", "repo", "head-sha")
        assertThat((result as GithubCompareResult).files.map { it.filename }).containsExactly("README.md", "src/Main.kt")
    }

    @Test
    @DisplayName("compare 결과 files가 비어있으면 head commit으로 fallback")
    fun givenCompareEmpty_whenExecuteDiffer_thenFallsBackToHeadCommit() {
        // given
        val service = buildService()
        val inquiry = GitDifferInquiry("owner", "repo", "base-sha", "head-sha", listOf("head-sha"))
        val emptyCompareResult = GithubCompareResult(files = emptyList())
        given(githubConnector.compare("owner", "repo", "base-sha...head-sha")).willReturn(emptyCompareResult)
        given(githubConnector.getCommit("owner", "repo", "head-sha")).willReturn(
            GithubCompareResult(files = listOf(GithubFile(filename = "src/Main.kt", status = "modified"))),
        )

        // when
        val result = service.executeInquiryDiffer(inquiry)

        // then
        verify(githubConnector).compare("owner", "repo", "base-sha...head-sha")
        verify(githubConnector).getCommit("owner", "repo", "head-sha")
        assertThat((result as GithubCompareResult).files.map { it.filename }).containsExactly("src/Main.kt")
    }

    @Test
    @DisplayName("compare가 errorMessage를 반환하면 head commit으로 fallback")
    fun givenCompareError_whenExecuteDiffer_thenFallsBackToHeadCommit() {
        // given
        val service = buildService()
        val inquiry = GitDifferInquiry("owner", "repo", "base-sha", "head-sha", listOf("head-sha"))
        val errorResult = GithubCompareResult(files = emptyList(), errorMessage = "[404] Not Found")
        given(githubConnector.compare("owner", "repo", "base-sha...head-sha")).willReturn(errorResult)
        given(githubConnector.getCommit("owner", "repo", "head-sha")).willReturn(
            GithubCompareResult(files = listOf(GithubFile(filename = "src/Main.kt", status = "modified"))),
        )

        // when
        val result = service.executeInquiryDiffer(inquiry)

        // then
        verify(githubConnector).compare("owner", "repo", "base-sha...head-sha")
        verify(githubConnector).getCommit("owner", "repo", "head-sha")
        assertThat((result as GithubCompareResult).files.map { it.filename }).containsExactly("src/Main.kt")
    }

    @Test
    @DisplayName("compare 호출 시 base...head 형식으로 basehead 구성")
    fun givenInquiry_whenExecuteDiffer_thenCallsCompareWithBasehead() {
        // given
        val service = buildService()
        val inquiry = GitDifferInquiry("walter", "my-repo", "abc123", "def456")
        given(githubConnector.compare("walter", "my-repo", "abc123...def456")).willReturn(
            GithubCompareResult(files = listOf(GithubFile(filename = "src/Main.kt", status = "modified"))),
        )

        // when
        service.executeInquiryDiffer(inquiry)

        // then
        verify(githubConnector).compare("walter", "my-repo", "abc123...def456")
    }

    // ── saveCodeReviewRecord ──────────────────────────────────────────────────

    @Test
    @DisplayName("saveCodeReviewRecord 호출 시 Redis ZSet에 저장")
    fun givenValidRecord_whenSaveRecord_thenPushesToRedis() {
        // given
        val service = buildService()
        val record = CodeReviewRecord(LocalDateTime.now(), "my-app", "https://github.com/owner/repo/commit/abc", "feat: add feature", emptyList(), "## Review", LocalDateTime.now(), emptyList(), null)
        given(redisTemplate.opsForZSet()).willReturn(mock())
        given(objectMapper.writeValueAsString(record)).willReturn("""{"application":"my-app"}""")

        // when / then — 예외 없이 실행되면 성공
        runCatching { service.saveCodeReviewRecord(record) }
    }

    // ── getCodeReviewRecords ──────────────────────────────────────────────────

    @Test
    @DisplayName("Redis가 null을 반환하면 빈 목록 반환")
    fun givenNullFromRedis_whenGetRecords_thenReturnsEmptyList() {
        // given
        val service = buildService()
        given(redisTemplate.opsForZSet()).willReturn(mock())

        // when
        val result = runCatching { service.getCodeReviewRecords("my-app") }.getOrDefault(emptyList())

        // then
        assertThat(result).isEmpty()
    }

    // ── postPullRequestComment ────────────────────────────────────────────────

    @Test
    @DisplayName("토큰이 없으면 코멘트 게시를 건너뜀")
    fun givenNoToken_whenPostComment_thenSkips() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_TOKEN)).willReturn(null)
        val inquiry = GitDifferInquiry("acme", "my-repo", "base", "head")

        // when
        service.postPullRequestComment(inquiry, 42, "review body")

        // then
        verifyNoInteractions(githubConnector)
    }

    @Test
    @DisplayName("body가 비어있으면 코멘트 게시를 건너뜀")
    fun givenBlankBody_whenPostComment_thenSkips() {
        // given
        val service = buildService(configuredToken = "config-token")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_TOKEN)).willReturn(null)
        val inquiry = GitDifferInquiry("acme", "my-repo", "base", "head")

        // when
        service.postPullRequestComment(inquiry, 42, "   ")

        // then
        verifyNoInteractions(githubConnector)
    }

    @Test
    @DisplayName("토큰이 있고 body가 있으면 formatPullRequestComment로 래핑 후 createIssueComment 호출")
    fun givenTokenAndBody_whenPostComment_thenCallsCreateIssueComment() {
        // given
        val service = buildService(configuredToken = "config-token")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_TOKEN)).willReturn(null)
        given(githubConnector.createIssueComment(anyString(), anyString(), anyInt(), any(GitCommentRequest::class.java) ?: GitCommentRequest("")))
            .willReturn(GithubIssueCommentResponse(id = 999L))
        val inquiry = GitDifferInquiry("acme", "my-repo", "base", "head")

        // when
        service.postPullRequestComment(inquiry, 42, "## Review\nBody")

        // then — service wraps the body via GitRemoteService.formatPullRequestComment
        val expectedBody = service.formatPullRequestComment("## Review\nBody")
        verify(githubConnector).createIssueComment("acme", "my-repo", 42, GitCommentRequest(expectedBody))
    }

    // ── postPullRequestInlineComments ─────────────────────────────────────────

    @Test
    @DisplayName("토큰이 없으면 인라인 리뷰 게시를 건너뛰고 false 반환")
    fun givenNoToken_whenPostInline_thenReturnsFalse() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_TOKEN)).willReturn(null)
        val inquiry = GitDifferInquiry("acme", "my-repo", "base", "head")
        val review = LlmInlineReviewResult("summary", listOf(LlmInlineComment("a.kt", 1, DiffSide.RIGHT, "x")))
        val compareResult = GithubCompareResult(files = listOf(GithubFile(filename = "a.kt", patch = "@@ -0,0 +1,1 @@\n+x")))

        // when
        val result = service.postPullRequestInlineComments(inquiry, 42, review, compareResult)

        // then
        assertThat(result).isFalse()
        verifyNoInteractions(githubConnector)
    }

    @Test
    @DisplayName("head SHA가 비어있으면 false 반환")
    fun givenBlankHeadSha_whenPostInline_thenReturnsFalse() {
        // given
        val service = buildService(configuredToken = "config-token")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_TOKEN)).willReturn(null)
        val inquiry = GitDifferInquiry("acme", "my-repo", "base", "")
        val review = LlmInlineReviewResult("s", listOf(LlmInlineComment("a.kt", 1, DiffSide.RIGHT, "x")))
        val compareResult = GithubCompareResult(files = listOf(GithubFile(filename = "a.kt", patch = "@@ -0,0 +1,1 @@\n+x")))

        // when
        val result = service.postPullRequestInlineComments(inquiry, 42, review, compareResult)

        // then
        assertThat(result).isFalse()
    }

    @Test
    @DisplayName("모든 코멘트가 diff 밖 라인이면 false 반환하고 API 호출 없음")
    fun givenAllCommentsOutsideDiff_whenPostInline_thenReturnsFalse() {
        // given
        val service = buildService(configuredToken = "config-token")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_TOKEN)).willReturn(null)
        val inquiry = GitDifferInquiry("acme", "my-repo", "base", "head")
        val review = LlmInlineReviewResult("s", listOf(LlmInlineComment("a.kt", 999, DiffSide.RIGHT, "x")))
        val compareResult = GithubCompareResult(files = listOf(GithubFile(filename = "a.kt", patch = "@@ -0,0 +1,1 @@\n+x")))

        // when
        val result = service.postPullRequestInlineComments(inquiry, 42, review, compareResult)

        // then
        assertThat(result).isFalse()
        verifyNoInteractions(githubConnector)
    }

    @Test
    @DisplayName("정상 케이스면 createReview를 호출하고 true 반환")
    fun givenValidComments_whenPostInline_thenCallsCreateReview() {
        // given
        val service = buildService(configuredToken = "config-token")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_TOKEN)).willReturn(null)
        val inquiry = GitDifferInquiry("acme", "my-repo", "base", "head-sha")
        val review = LlmInlineReviewResult(
            summary = "Overall LGTM",
            comments = listOf(LlmInlineComment("a.kt", 1, DiffSide.RIGHT, "consider null check")),
        )
        val compareResult = GithubCompareResult(files = listOf(GithubFile(filename = "a.kt", patch = "@@ -0,0 +1,1 @@\n+line")))
        val expectedRequest = GithubReviewRequest(
            commitId = "head-sha",
            body = "Overall LGTM",
            event = "COMMENT",
            comments = listOf(GithubReviewComment(path = "a.kt", line = 1, side = "RIGHT", body = "consider null check")),
        )
        given(githubConnector.createReview("acme", "my-repo", 42, expectedRequest))
            .willReturn(GithubReviewResponse(id = 500L))

        // when
        val result = service.postPullRequestInlineComments(inquiry, 42, review, compareResult)

        // then
        assertThat(result).isTrue()
        verify(githubConnector).createReview("acme", "my-repo", 42, expectedRequest)
    }

    @Test
    @DisplayName("createReview 응답에 errorMessage가 있으면 false 반환")
    fun givenApiError_whenPostInline_thenReturnsFalse() {
        // given
        val service = buildService(configuredToken = "config-token")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITHUB_TOKEN)).willReturn(null)
        val inquiry = GitDifferInquiry("acme", "my-repo", "base", "head")
        val review = LlmInlineReviewResult("s", listOf(LlmInlineComment("a.kt", 1, DiffSide.RIGHT, "x")))
        val compareResult = GithubCompareResult(files = listOf(GithubFile(filename = "a.kt", patch = "@@ -0,0 +1,1 @@\n+x")))
        val expectedRequest = GithubReviewRequest(
            commitId = "head",
            body = "s",
            event = "COMMENT",
            comments = listOf(GithubReviewComment(path = "a.kt", line = 1, side = "RIGHT", body = "x")),
        )
        given(githubConnector.createReview("acme", "my-repo", 42, expectedRequest))
            .willReturn(GithubReviewResponse(errorMessage = "422 Unprocessable Entity"))

        // when
        val result = service.postPullRequestInlineComments(inquiry, 42, review, compareResult)

        // then
        assertThat(result).isFalse()
    }

    private inline fun <reified T> mock(): T = mock(T::class.java)
}
