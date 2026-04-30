package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.connector.GitlabConnector
import com.walter.spring.ai.ops.connector.dto.GitDifferInquiry
import com.walter.spring.ai.ops.connector.dto.GitlabApiCommit
import com.walter.spring.ai.ops.connector.dto.GitlabCompareResult
import com.walter.spring.ai.ops.connector.dto.GitlabFile
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
import org.springframework.data.redis.core.StringRedisTemplate

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GitlabServiceTest {

    @Mock private lateinit var redisTemplate: StringRedisTemplate
    @Mock private lateinit var objectMapper: ObjectMapper
    @Mock private lateinit var cryptoProvider: CryptoProvider
    @Mock private lateinit var gitlabConnector: GitlabConnector

    @BeforeEach
    fun setUp() {
        given(cryptoProvider.encrypt(anyString())).willAnswer { it.getArgument(0) }
        given(cryptoProvider.decrypt(anyString())).willAnswer { it.getArgument(0) }
    }

    @Test
    @DisplayName("before가 전부 0이면 push에 포함된 모든 commit diff를 조회")
    fun givenEmptyShaBase_whenExecuteInquiryDiffer_thenCallsGetCommitDiffForAllPushedCommits() {
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
    fun givenMultiplePushedCommits_whenExecuteInquiryDiffer_thenCallsGetCommitDiffForAllPushedCommits() {
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
    fun givenCompareReturnsEmptyDiffs_whenExecuteInquiryDiffer_thenFallsBackToHeadCommitDiff() {
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

    private fun buildService() =
        GitlabService(redisTemplate, objectMapper, cryptoProvider, gitlabConnector, 120L, 5L, "", "https://gitlab.com/api/v4")
}
