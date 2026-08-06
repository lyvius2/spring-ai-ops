package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.code.GitRemoteProvider
import com.walter.spring.ai.ops.code.PullRequestAction
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.GithubFile
import com.walter.spring.ai.ops.controller.dto.AppUpdateRequest
import com.walter.spring.ai.ops.controller.dto.GithubPullRequestRequest
import com.walter.spring.ai.ops.controller.dto.GithubPushCommit
import com.walter.spring.ai.ops.controller.dto.GithubPushOwner
import com.walter.spring.ai.ops.controller.dto.GithubPushRepository
import com.walter.spring.ai.ops.controller.dto.GithubPushRequest
import com.walter.spring.ai.ops.record.CodeReviewRecord
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.MessageService
import com.walter.spring.ai.ops.util.GitRemoteResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.context.ApplicationEventPublisher

@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = Mockito.any() as T

@ExtendWith(MockitoExtension::class)
class CodeReviewFacadeTest {

    @Mock private lateinit var applicationService: ApplicationService
    @Mock private lateinit var gitRemoteResolver: GitRemoteResolver
    @Mock private lateinit var aiModelService: AiModelService
    @Mock private lateinit var messageService: MessageService
    @Mock private lateinit var eventPublisher: ApplicationEventPublisher
    @Mock private lateinit var githubService: GithubService

    private lateinit var codeReviewFacade: CodeReviewFacade

    @BeforeEach
    fun setUp() {
        codeReviewFacade = CodeReviewFacade(
            applicationService, gitRemoteResolver, aiModelService, messageService, eventPublisher,
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun createRequest(
        before: String = "abc1234",
        after: String = "def5678",
        ref: String = "refs/heads/main",
        commits: List<GithubPushCommit> = listOf(
            GithubPushCommit(id = "sha1", message = "fix: NPE in PaymentService", url = "https://github.com/org/repo/commit/sha1", timestamp = "2026-05-24T10:00:00+09:00"),
        ),
        source: GitRemoteProvider = GitRemoteProvider.GITHUB,
    ) = GithubPushRequest(
        before = before,
        after = after,
        ref = ref,
        repository = GithubPushRepository(
            name = "my-repo",
            owner = GithubPushOwner(login = "acme"),
            htmlUrl = "https://github.com/acme/my-repo",
        ),
        commits = commits,
        source = source,
    )

    /** Happy-path stub: token configured, diff returns compareResult, LLM returns review text */
    private fun stubTokenConfiguredAndDiff(compareResult: GithubCompareResult = GithubCompareResult()) {
        `when`(gitRemoteResolver.detectProviderService(anyObj())).thenReturn(githubService)
        `when`(githubService.isTokenConfigured()).thenReturn(true)
        `when`(githubService.executeInquiryDiffer(anyObj())).thenReturn(compareResult)
        `when`(aiModelService.executeAnalyzeCodeDiffer(anyObj())).thenReturn("## Review\nLooks good.")
    }

    /** Captures the CodeReviewRecord saved to the git service during facade execution */
    private fun captureOnSave(block: () -> Unit): CodeReviewRecord {
        var saved: CodeReviewRecord? = null
        doAnswer { invocation ->
            saved = invocation.arguments[0] as CodeReviewRecord
            null
        }.`when`(githubService).saveCodeReviewRecord(anyObj())
        block()
        return requireNotNull(saved) { "saveCodeReviewRecord was never called" }
    }

    // ── analyzeCodeDiffer ─────────────────────────────────────────────────────

    @Test
    @DisplayName("commits이 비어있으면 어떤 서비스도 호출되지 않음")
    fun analyzeCodeDiffer_callsNoServices_whenCommitsIsEmpty() {
        // given
        val request = createRequest(commits = emptyList())

        // when
        codeReviewFacade.analyzeCodeDiffer(request, "my-app")

        // then
        verifyNoInteractions(applicationService, gitRemoteResolver, aiModelService, messageService, eventPublisher)
    }

    @Test
    @DisplayName("commits이 있으면 애플리케이션을 등록함")
    fun analyzeCodeDiffer_registersApplication_whenCommitsPresent() {
        // given
        val request = createRequest()
        stubTokenConfiguredAndDiff()

        // when
        codeReviewFacade.analyzeCodeDiffer(request, "my-app")

        // then
        verify(applicationService).addApp(AppUpdateRequest("my-app"))
    }

    @Test
    @DisplayName("application이 null이면 'Unknown Application'으로 등록됨")
    fun analyzeCodeDiffer_registersUnknownApplication_whenApplicationIsNull() {
        // given
        val request = createRequest()
        stubTokenConfiguredAndDiff()

        // when
        codeReviewFacade.analyzeCodeDiffer(request, null)

        // then
        verify(applicationService).addApp(AppUpdateRequest("Unknown Application"))
    }

    @Test
    @DisplayName("Git 토큰이 설정되지 않으면 에러 레코드를 저장하고 WebSocket으로 전송함")
    fun analyzeCodeDiffer_savesErrorRecordAndPushes_whenTokenNotConfigured() {
        // given
        val request = createRequest()
        `when`(gitRemoteResolver.detectProviderService(anyObj())).thenReturn(githubService)
        `when`(githubService.isTokenConfigured()).thenReturn(false)

        // when
        codeReviewFacade.analyzeCodeDiffer(request, "my-app")

        // then
        verify(githubService).saveCodeReviewRecord(anyObj())
        verify(messageService).pushCodeReview(anyObj())
        verify(aiModelService, never()).executeAnalyzeCodeDiffer(anyObj())
        verify(eventPublisher, never()).publishEvent(anyObj())
    }

    @Test
    @DisplayName("Git 토큰이 없으면 LLM 분석 없이 provider의 alertMessage를 에러 레코드 review 결과로 저장함")
    fun analyzeCodeDiffer_usesAlertMessageAsReviewResult_whenTokenNotConfigured() {
        // given
        val request = createRequest()
        `when`(gitRemoteResolver.detectProviderService(anyObj())).thenReturn(githubService)
        `when`(githubService.isTokenConfigured()).thenReturn(false)

        // when
        val record = captureOnSave { codeReviewFacade.analyzeCodeDiffer(request, "my-app") }

        // then
        assertThat(record.reviewResult()).isEqualTo(GitRemoteProvider.GITHUB.alertMessage)
    }

    @Test
    @DisplayName("Git 토큰이 설정되면 LLM 분석을 실행함")
    fun analyzeCodeDiffer_executesLlmReview_whenTokenConfigured() {
        // given
        val request = createRequest()
        stubTokenConfiguredAndDiff()

        // when
        codeReviewFacade.analyzeCodeDiffer(request, "my-app")

        // then
        verify(aiModelService).executeAnalyzeCodeDiffer(anyObj())
    }

    @Test
    @DisplayName("코드 리뷰 완료 후 레코드를 Git 서비스에 저장함")
    fun analyzeCodeDiffer_savesCodeReviewRecord_afterReview() {
        // given
        val request = createRequest()
        stubTokenConfiguredAndDiff()

        // when
        codeReviewFacade.analyzeCodeDiffer(request, "my-app")

        // then
        verify(githubService).saveCodeReviewRecord(anyObj())
    }

    @Test
    @DisplayName("코드 리뷰 완료 후 WebSocket으로 레코드를 전송함")
    fun analyzeCodeDiffer_pushesCodeReviewRecordViaWebSocket_afterReview() {
        // given
        val request = createRequest()
        stubTokenConfiguredAndDiff()

        // when
        codeReviewFacade.analyzeCodeDiffer(request, "my-app")

        // then
        verify(messageService).pushCodeReview(anyObj())
    }

    @Test
    @DisplayName("코드 리뷰 완료 후 CodeReviewCompletedEvent를 발행함")
    fun analyzeCodeDiffer_publishesCodeReviewCompletedEvent_afterReview() {
        // given
        val request = createRequest()
        stubTokenConfiguredAndDiff()

        // when
        codeReviewFacade.analyzeCodeDiffer(request, "my-app")

        // then
        verify(eventPublisher).publishEvent(anyObj())
    }

    @Test
    @DisplayName("새 브랜치 push이면 repoUrl에 commit URL 형식이 사용됨")
    fun analyzeCodeDiffer_usesCommitUrl_whenNewBranch() {
        // given
        val request = createRequest(before = GithubPushRequest.EMPTY_SHA, after = "def5678")
        stubTokenConfiguredAndDiff()

        // when
        val record = captureOnSave { codeReviewFacade.analyzeCodeDiffer(request, "my-app") }

        // then
        assertThat(record.githubUrl()).contains("/commit/def5678")
        assertThat(record.githubUrl()).doesNotContain("/compare/")
    }

    @Test
    @DisplayName("기존 브랜치 push이면 repoUrl에 compare URL 형식이 사용됨")
    fun analyzeCodeDiffer_usesCompareUrl_whenExistingBranch() {
        // given
        val request = createRequest(before = "abc1234", after = "def5678")
        stubTokenConfiguredAndDiff()

        // when
        val record = captureOnSave { codeReviewFacade.analyzeCodeDiffer(request, "my-app") }

        // then
        assertThat(record.githubUrl()).contains("/compare/abc1234...def5678")
    }

    @Test
    @DisplayName("레코드에 application 이름이 올바르게 설정됨")
    fun analyzeCodeDiffer_setsApplicationNameOnRecord() {
        // given
        val request = createRequest()
        val compareResult = GithubCompareResult(
            files = listOf(GithubFile(filename = "src/Main.kt", status = "modified", additions = 2, deletions = 1, patch = "@@ -1 +1 @@")),
        )
        stubTokenConfiguredAndDiff(compareResult)

        // when
        val record = captureOnSave { codeReviewFacade.analyzeCodeDiffer(request, "my-app") }

        // then
        assertThat(record.application()).isEqualTo("my-app")
    }

    @Test
    @DisplayName("레코드의 branch 필드에서 refs/heads/ 접두사가 제거됨")
    fun analyzeCodeDiffer_stripsBranchPrefix_onRecord() {
        // given
        val request = createRequest(ref = "refs/heads/feature/my-feature")
        stubTokenConfiguredAndDiff()

        // when
        val record = captureOnSave { codeReviewFacade.analyzeCodeDiffer(request, "my-app") }

        // then
        assertThat(record.branch()).isEqualTo("feature/my-feature")
    }

    @Test
    @DisplayName("LLM 분석 중 예외가 발생해도 facade는 예외를 전파하지 않음")
    fun analyzeCodeDiffer_doesNotPropagateException_whenLlmThrows() {
        // given
        val request = createRequest()
        `when`(gitRemoteResolver.detectProviderService(anyObj())).thenReturn(githubService)
        `when`(githubService.isTokenConfigured()).thenReturn(true)
        `when`(githubService.executeInquiryDiffer(anyObj())).thenThrow(RuntimeException("diff API error"))

        // when / then — no exception thrown
        codeReviewFacade.analyzeCodeDiffer(request, "my-app")

        // then
        verify(messageService, never()).pushCodeReview(anyObj())
    }

    // ── analyzePullRequest ────────────────────────────────────────────────────

    private fun createPullRequestRequest(
        action: PullRequestAction = PullRequestAction.OPENED,
        draft: Boolean = false,
        number: Int = 42,
        baseSha: String = "base-sha",
        headSha: String = "head-sha",
        source: GitRemoteProvider = GitRemoteProvider.GITHUB,
    ) = GithubPullRequestRequest(
        action = action,
        number = number,
        title = "Add PR review feature",
        baseSha = baseSha,
        headSha = headSha,
        draft = draft,
        repository = GithubPushRepository(
            name = "my-repo",
            owner = GithubPushOwner(login = "acme"),
            htmlUrl = "https://github.com/acme/my-repo",
        ),
        htmlUrl = "https://github.com/acme/my-repo/pull/42",
        source = source,
    )

    @Test
    @DisplayName("IGNORED action이면 아무 서비스도 호출되지 않음")
    fun analyzePullRequest_skips_whenActionIsIgnored() {
        // given
        val request = createPullRequestRequest(action = PullRequestAction.IGNORED)

        // when
        codeReviewFacade.analyzePullRequest(request, "my-app")

        // then
        verifyNoInteractions(applicationService, gitRemoteResolver, aiModelService, messageService, eventPublisher)
    }

    @Test
    @DisplayName("draft PR이면 리뷰를 건너뜀")
    fun analyzePullRequest_skips_whenDraftIsTrue() {
        // given
        val request = createPullRequestRequest(action = PullRequestAction.OPENED, draft = true)

        // when
        codeReviewFacade.analyzePullRequest(request, "my-app")

        // then
        verifyNoInteractions(gitRemoteResolver, aiModelService)
    }

    @Test
    @DisplayName("SYNCHRONIZE action은 v1에서 스킵됨 (v2 미구현)")
    fun analyzePullRequest_skips_whenActionIsSynchronize() {
        // given
        val request = createPullRequestRequest(action = PullRequestAction.SYNCHRONIZE)

        // when
        codeReviewFacade.analyzePullRequest(request, "my-app")

        // then
        verifyNoInteractions(gitRemoteResolver, aiModelService)
    }

    @Test
    @DisplayName("number가 0이면 리뷰를 건너뜀")
    fun analyzePullRequest_skips_whenNumberIsZero() {
        // given
        val request = createPullRequestRequest(number = 0)

        // when
        codeReviewFacade.analyzePullRequest(request, "my-app")

        // then
        verifyNoInteractions(gitRemoteResolver, aiModelService)
    }

    @Test
    @DisplayName("baseSha 또는 headSha가 비어있으면 리뷰를 건너뜀")
    fun analyzePullRequest_skips_whenBaseOrHeadIsBlank() {
        // given
        val request = createPullRequestRequest(baseSha = "")

        // when
        codeReviewFacade.analyzePullRequest(request, "my-app")

        // then
        verifyNoInteractions(gitRemoteResolver, aiModelService)
    }

    @Test
    @DisplayName("토큰이 설정되어 있지 않으면 LLM/코멘트 호출 없이 종료")
    fun analyzePullRequest_skips_whenTokenNotConfigured() {
        // given
        val request = createPullRequestRequest()
        `when`(gitRemoteResolver.detectProviderService(anyObj())).thenReturn(githubService)
        `when`(githubService.isTokenConfigured()).thenReturn(false)

        // when
        codeReviewFacade.analyzePullRequest(request, "my-app")

        // then
        verify(aiModelService, never()).executeAnalyzeCodeDiffer(anyObj())
        verify(githubService, never()).postPullRequestComment(anyObj(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    @DisplayName("diff 조회에 실패하면 LLM/코멘트 호출 없이 종료")
    fun analyzePullRequest_skips_whenDifferHasError() {
        // given
        val request = createPullRequestRequest()
        `when`(gitRemoteResolver.detectProviderService(anyObj())).thenReturn(githubService)
        `when`(githubService.isTokenConfigured()).thenReturn(true)
        `when`(githubService.executeInquiryDiffer(anyObj())).thenReturn(GithubCompareResult(errorMessage = "not found"))

        // when
        codeReviewFacade.analyzePullRequest(request, "my-app")

        // then
        verify(aiModelService, never()).executeAnalyzeCodeDiffer(anyObj())
        verify(githubService, never()).postPullRequestComment(anyObj(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    @DisplayName("LLM 리뷰 결과가 비어있으면 코멘트 게시를 스킵함")
    fun analyzePullRequest_skipsPostComment_whenLlmResultIsBlank() {
        // given
        val request = createPullRequestRequest()
        `when`(gitRemoteResolver.detectProviderService(anyObj())).thenReturn(githubService)
        `when`(githubService.isTokenConfigured()).thenReturn(true)
        `when`(githubService.executeInquiryDiffer(anyObj())).thenReturn(
            GithubCompareResult(files = listOf(GithubFile(filename = "src/Main.kt", status = "modified", patch = "@@")))
        )
        `when`(aiModelService.executeAnalyzeCodeDiffer(anyObj())).thenReturn("")

        // when
        codeReviewFacade.analyzePullRequest(request, "my-app")

        // then
        verify(githubService, never()).postPullRequestComment(anyObj(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    @DisplayName("OPENED + 정상 데이터이면 애플리케이션을 등록하고 코멘트를 게시함")
    fun analyzePullRequest_postsComment_onHappyPath() {
        // given
        val request = createPullRequestRequest()
        `when`(gitRemoteResolver.detectProviderService(anyObj())).thenReturn(githubService)
        `when`(githubService.isTokenConfigured()).thenReturn(true)
        `when`(githubService.executeInquiryDiffer(anyObj())).thenReturn(
            GithubCompareResult(files = listOf(GithubFile(filename = "src/Main.kt", status = "modified", patch = "@@")))
        )
        `when`(aiModelService.executeAnalyzeCodeDiffer(anyObj())).thenReturn("## Review\nLooks solid.")

        // when
        codeReviewFacade.analyzePullRequest(request, "my-app")

        // then
        verify(applicationService).addApp(AppUpdateRequest("my-app"))
        verify(aiModelService).executeAnalyzeCodeDiffer(anyObj())
        verify(githubService).postPullRequestComment(anyObj(), org.mockito.ArgumentMatchers.eq(42), org.mockito.ArgumentMatchers.contains("Looks solid."))
    }

    @Test
    @DisplayName("리뷰 처리 중 예외가 발생해도 facade는 예외를 전파하지 않음")
    fun analyzePullRequest_doesNotPropagateException_whenLlmThrows() {
        // given
        val request = createPullRequestRequest()
        `when`(gitRemoteResolver.detectProviderService(anyObj())).thenReturn(githubService)
        `when`(githubService.isTokenConfigured()).thenReturn(true)
        `when`(githubService.executeInquiryDiffer(anyObj())).thenThrow(RuntimeException("diff API error"))

        // when / then — no exception thrown
        codeReviewFacade.analyzePullRequest(request, "my-app")

        // then
        verify(githubService, never()).postPullRequestComment(anyObj(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString())
    }
}
