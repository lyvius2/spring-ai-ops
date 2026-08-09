package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITLAB_TOKEN
import com.walter.spring.ai.ops.connector.GitlabConnector
import com.walter.spring.ai.ops.connector.dto.GitCommentRequest
import com.walter.spring.ai.ops.connector.dto.GitDifferInquiry
import com.walter.spring.ai.ops.code.DiffSide
import com.walter.spring.ai.ops.connector.dto.GitlabApiCommit
import com.walter.spring.ai.ops.connector.dto.GitlabCompareResult
import com.walter.spring.ai.ops.connector.dto.GitlabDiscussionPosition
import com.walter.spring.ai.ops.connector.dto.GitlabDiscussionRequest
import com.walter.spring.ai.ops.connector.dto.GitlabDiscussionResponse
import com.walter.spring.ai.ops.connector.dto.GitlabFile
import com.walter.spring.ai.ops.connector.dto.GitlabMergeRequestNoteResponse
import com.walter.spring.ai.ops.service.dto.LlmInlineComment
import com.walter.spring.ai.ops.service.dto.LlmInlineReviewResult
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
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GitlabServiceTest {
    private var DEFAULT_DISCUSSION_REQ = GitlabDiscussionRequest(
        "", GitlabDiscussionPosition(baseSha = "", startSha = "", headSha = "")
    )

    @Mock private lateinit var redisTemplate: StringRedisTemplate
    @Mock private lateinit var objectMapper: ObjectMapper
    @Mock private lateinit var cryptoProvider: CryptoProvider
    @Mock private lateinit var gitlabConnector: GitlabConnector
    @Mock private lateinit var valueOperations: ValueOperations<String, String>

    @BeforeEach
    fun setUp() {
        given(cryptoProvider.encrypt(anyString())).willAnswer { it.getArgument(0) }
        given(cryptoProvider.decrypt(anyString())).willAnswer { it.getArgument(0) }
    }

    @Test
    @DisplayName("before가 전부 0이면 push에 포함된 모든 commit diff를 조회")
    fun givenEmptyShaBase_whenExecuteDiffer_thenFetchesAllCommitDiffs() {
        // given
        val service = buildService()
        val inquiry = GitDifferInquiry("group", "repo", GitRemoteService.EMPTY_SHA, "head-sha", listOf("first-sha", "head-sha"))
        given(gitlabConnector.getCommit("group%2Frepo", "first-sha")).willReturn(GitlabApiCommit(id = "first-sha"))
        given(gitlabConnector.getCommit("group%2Frepo", "head-sha")).willReturn(GitlabApiCommit(id = "head-sha"))
        given(gitlabConnector.getCommitDiff("group%2Frepo", "first-sha")).willReturn(
            listOf(GitlabFile(newPath = "README.md", newFile = true, diff = "+readme")),
        )
        given(gitlabConnector.getCommitDiff("group%2Frepo", "head-sha")).willReturn(
            listOf(GitlabFile(newPath = "src/Main.kt", diff = "+main")),
        )

        // when
        val result = service.executeInquiryDiffer(inquiry)

        // then
        verify(gitlabConnector).getCommit("group%2Frepo", "first-sha")
        verify(gitlabConnector).getCommit("group%2Frepo", "head-sha")
        verify(gitlabConnector).getCommitDiff("group%2Frepo", "first-sha")
        verify(gitlabConnector).getCommitDiff("group%2Frepo", "head-sha")
        assertThat((result as GitlabCompareResult).diffs.map { it.newPath }).containsExactly("README.md", "src/Main.kt")
    }

    @Test
    @DisplayName("push commit이 여러 개이면 모든 commit diff를 조회")
    fun givenMultiplePushedCommits_whenExecuteDiffer_thenFetchesAllCommitDiffs() {
        // given
        val service = buildService()
        val inquiry = GitDifferInquiry("group", "repo", "base-sha", "head-sha", listOf("first-sha", "head-sha"))
        given(gitlabConnector.getCommit("group%2Frepo", "first-sha")).willReturn(GitlabApiCommit(id = "first-sha"))
        given(gitlabConnector.getCommit("group%2Frepo", "head-sha")).willReturn(GitlabApiCommit(id = "head-sha"))
        given(gitlabConnector.getCommitDiff("group%2Frepo", "first-sha")).willReturn(
            listOf(GitlabFile(newPath = "README.md", newFile = true, diff = "+readme")),
        )
        given(gitlabConnector.getCommitDiff("group%2Frepo", "head-sha")).willReturn(
            listOf(GitlabFile(newPath = "src/Main.kt", diff = "+main")),
        )

        // when
        val result = service.executeInquiryDiffer(inquiry)

        // then
        verify(gitlabConnector).getCommitDiff("group%2Frepo", "first-sha")
        verify(gitlabConnector).getCommitDiff("group%2Frepo", "head-sha")
        assertThat((result as GitlabCompareResult).diffs.map { it.newPath }).containsExactly("README.md", "src/Main.kt")
    }

    @Test
    @DisplayName("compare 결과 diffs가 비어있으면 head commit diff로 fallback")
    fun givenCompareEmpty_whenExecuteDiffer_thenFallsBackToHeadDiff() {
        // given
        val service = buildService()
        val inquiry = GitDifferInquiry("group", "repo", "base-sha", "head-sha", listOf("head-sha"))
        given(gitlabConnector.compare("group%2Frepo", "base-sha", "head-sha")).willReturn(GitlabCompareResult())
        given(gitlabConnector.getCommit("group%2Frepo", "head-sha")).willReturn(GitlabApiCommit(id = "head-sha"))
        given(gitlabConnector.getCommitDiff("group%2Frepo", "head-sha")).willReturn(
            listOf(GitlabFile(newPath = "src/Main.kt", diff = "+main")),
        )

        // when
        val result = service.executeInquiryDiffer(inquiry)

        // then
        verify(gitlabConnector).compare("group%2Frepo", "base-sha", "head-sha")
        verify(gitlabConnector).getCommitDiff("group%2Frepo", "head-sha")
        assertThat((result as GitlabCompareResult).diffs.map { it.newPath }).containsExactly("src/Main.kt")
    }

    // ── postPullRequestComment ────────────────────────────────────────────────

    @Test
    @DisplayName("토큰이 없으면 MR 노트 게시를 건너뜀")
    fun givenNoToken_whenPostComment_thenSkips() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITLAB_TOKEN)).willReturn(null)
        val inquiry = GitDifferInquiry("group", "repo", "base", "head")

        // when
        service.postPullRequestComment(inquiry, 42, "note body")

        // then
        verifyNoInteractions(gitlabConnector)
    }

    @Test
    @DisplayName("body가 비어있으면 MR 노트 게시를 건너뜀")
    fun givenBlankBody_whenPostComment_thenSkips() {
        // given
        val service = buildService(configuredToken = "config-token")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITLAB_TOKEN)).willReturn(null)
        val inquiry = GitDifferInquiry("group", "repo", "base", "head")

        // when
        service.postPullRequestComment(inquiry, 42, "   ")

        // then
        verifyNoInteractions(gitlabConnector)
    }

    @Test
    @DisplayName("projectPath의 '/'가 %2F로 인코딩되어 MR 노트가 게시됨")
    fun givenTokenAndBody_whenPostComment_thenEncodesProjectPath() {
        // given
        val service = buildService(configuredToken = "config-token")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITLAB_TOKEN)).willReturn(null)
        given(gitlabConnector.createMergeRequestNote(anyString(), anyInt(), any(GitCommentRequest::class.java) ?: GitCommentRequest("")))
            .willReturn(GitlabMergeRequestNoteResponse(id = 999L))
        val inquiry = GitDifferInquiry("my-group", "my-repo", "base", "head")

        // when
        service.postPullRequestComment(inquiry, 42, "## Review\nBody")

        // then
        verify(gitlabConnector).createMergeRequestNote("my-group%2Fmy-repo", 42, GitCommentRequest("## Review\nBody"))
    }

    // ── postPullRequestInlineComments ─────────────────────────────────────────

    @Test
    @DisplayName("토큰이 없으면 인라인 리뷰 게시를 건너뛰고 false 반환")
    fun givenNoToken_whenPostInline_thenReturnsFalse() {
        // given
        val service = buildService()
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITLAB_TOKEN)).willReturn(null)
        val inquiry = GitDifferInquiry("group", "repo", "base", "head")
        val review = LlmInlineReviewResult("s", listOf(LlmInlineComment("a.kt", 1, DiffSide.RIGHT, "x")))
        val compareResult = GitlabCompareResult(diffs = listOf(GitlabFile(newPath = "a.kt", oldPath = "a.kt", diff = "@@ -0,0 +1,1 @@\n+x")))

        // when
        val result = service.postPullRequestInlineComments(inquiry, 42, review, compareResult)

        // then
        assertThat(result).isFalse()
        verifyNoInteractions(gitlabConnector)
    }

    @Test
    @DisplayName("base 또는 head SHA가 비어있으면 false 반환")
    fun givenBlankSha_whenPostInline_thenReturnsFalse() {
        // given
        val service = buildService(configuredToken = "config-token")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITLAB_TOKEN)).willReturn(null)
        val inquiry = GitDifferInquiry("group", "repo", "", "head")
        val review = LlmInlineReviewResult("s", listOf(LlmInlineComment("a.kt", 1, DiffSide.RIGHT, "x")))
        val compareResult = GitlabCompareResult(diffs = listOf(GitlabFile(newPath = "a.kt", oldPath = "a.kt", diff = "@@ -0,0 +1,1 @@\n+x")))

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
        given(valueOperations.get(REDIS_KEY_GITLAB_TOKEN)).willReturn(null)
        val inquiry = GitDifferInquiry("group", "repo", "base", "head")
        val review = LlmInlineReviewResult("s", listOf(LlmInlineComment("a.kt", 999, DiffSide.RIGHT, "x")))
        val compareResult = GitlabCompareResult(diffs = listOf(GitlabFile(newPath = "a.kt", oldPath = "a.kt", diff = "@@ -0,0 +1,1 @@\n+x")))

        // when
        val result = service.postPullRequestInlineComments(inquiry, 42, review, compareResult)

        // then
        assertThat(result).isFalse()
        verifyNoInteractions(gitlabConnector)
    }

    @Test
    @DisplayName("정상 케이스면 discussion N개와 summary note를 게시하고 true 반환")
    fun givenValidComments_whenPostInline_thenPostsDiscussionsWithSummary() {
        // given
        val service = buildService(configuredToken = "config-token")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITLAB_TOKEN)).willReturn(null)
        val inquiry = GitDifferInquiry("group", "repo", "base-sha", "head-sha")
        val review = LlmInlineReviewResult(
            summary = "Overall LGTM",
            comments = listOf(LlmInlineComment("a.kt", 1, DiffSide.RIGHT, "consider null check")),
        )
        val compareResult = GitlabCompareResult(diffs = listOf(GitlabFile(newPath = "a.kt", oldPath = "a.kt", diff = "@@ -0,0 +1,1 @@\n+line")))
        given(gitlabConnector.createMrDiscussion(anyString(), anyInt(), any(GitlabDiscussionRequest::class.java) ?: DEFAULT_DISCUSSION_REQ))
            .willReturn(GitlabDiscussionResponse(id = "abc"))
        given(gitlabConnector.createMergeRequestNote(anyString(), anyInt(), any(GitCommentRequest::class.java) ?: GitCommentRequest("")))
            .willReturn(GitlabMergeRequestNoteResponse(id = 999L))

        // when
        val result = service.postPullRequestInlineComments(inquiry, 42, review, compareResult)

        // then
        assertThat(result).isTrue()
        verify(gitlabConnector).createMrDiscussion(anyString(), anyInt(), any(GitlabDiscussionRequest::class.java) ?: DEFAULT_DISCUSSION_REQ)
        verify(gitlabConnector).createMergeRequestNote("group%2Frepo", 42, GitCommentRequest("Overall LGTM"))
    }

    @Test
    @DisplayName("createMrDiscussion 응답에 errorMessage가 있으면 false 반환")
    fun givenApiError_whenPostInline_thenReturnsFalse() {
        // given
        val service = buildService(configuredToken = "config-token")
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get(REDIS_KEY_GITLAB_TOKEN)).willReturn(null)
        val inquiry = GitDifferInquiry("group", "repo", "base", "head")
        val review = LlmInlineReviewResult("s", listOf(LlmInlineComment("a.kt", 1, DiffSide.RIGHT, "x")))
        val compareResult = GitlabCompareResult(diffs = listOf(GitlabFile(newPath = "a.kt", oldPath = "a.kt", diff = "@@ -0,0 +1,1 @@\n+x")))
        given(gitlabConnector.createMrDiscussion(anyString(), anyInt(), any(GitlabDiscussionRequest::class.java) ?: DEFAULT_DISCUSSION_REQ))
            .willReturn(GitlabDiscussionResponse(errorMessage = "422 invalid position"))

        // when
        val result = service.postPullRequestInlineComments(inquiry, 42, review, compareResult)

        // then
        assertThat(result).isFalse()
    }

    private fun buildService(configuredToken: String = "") =
        GitlabService(redisTemplate, objectMapper, cryptoProvider, gitlabConnector, 120L, 5L, configuredToken, "https://gitlab.com/api/v4")
}
