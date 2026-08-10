package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.code.PullRequestAction
import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.connector.dto.GitCompareResult
import com.walter.spring.ai.ops.controller.dto.AppUpdateRequest
import com.walter.spring.ai.ops.connector.dto.GitDifferInquiry
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.controller.dto.GithubPullRequestRequest
import com.walter.spring.ai.ops.controller.dto.GithubPushRequest
import com.walter.spring.ai.ops.event.CodeReviewCompletedEvent
import com.walter.spring.ai.ops.record.CodeReviewRecord
import com.walter.spring.ai.ops.record.CommitSummary
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GitRemoteService
import com.walter.spring.ai.ops.service.MessageService
import com.walter.spring.ai.ops.util.GitRemoteResolver
import com.walter.spring.ai.ops.util.InlineReviewParser
import com.walter.spring.ai.ops.util.extension.toISO8601
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDateTime

@Facade
class CodeReviewFacade(
    private val applicationService: ApplicationService,
    private val gitRemoteResolver: GitRemoteResolver,
    private val aiModelService: AiModelService,
    private val messageService: MessageService,
    private val eventPublisher: ApplicationEventPublisher,
    private val inlineReviewParser: InlineReviewParser,
) {
    private val log = LoggerFactory.getLogger(CodeReviewFacade::class.java)

    fun analyzeCodeDiffer(request: GithubPushRequest, application: String?) {
        if (request.commits.isEmpty()) {
            log.warn("Git push webhook skipped — no commits (ping or empty push)")
            return
        }
        recordAuditLog(request)
        runCatching {
            val targetApplication = application ?: "Unknown Application"
            applicationService.addApp(AppUpdateRequest(targetApplication))
            val gitService = gitRemoteResolver.detectProviderService(request.source)

            if (!gitService.isTokenConfigured()) {
                log.warn("Git push webhook received but {} token is not configured", request.source)
                val errorRecord = createCodeReviewRecord(
                    request, targetApplication,
                    GithubCompareResult(),
                    request.source.alertMessage,
                )
                gitService.saveCodeReviewRecord(errorRecord)
                messageService.pushCodeReview(errorRecord)
                return@runCatching
            }

            val inquiry = GitDifferInquiry.of(request)
            val compareResult = gitService.executeInquiryDiffer(inquiry)
            val reviewResult = aiModelService.executeAnalyzeCodeDiffer(compareResult.createCodeReviewPrompt())
            val record = createCodeReviewRecord(request, targetApplication, compareResult, reviewResult)
            gitService.saveCodeReviewRecord(record)
            messageService.pushCodeReview(record)
            eventPublisher.publishEvent(CodeReviewCompletedEvent(record, targetApplication))
        }.onFailure { log.error("Failed to analyze code review record : {}", it.message, it) }
    }

    private fun recordAuditLog(request: GithubPushRequest) {
        log.info(
            "Git push webhook received — repo: {}, before: {}, after: {}, commits: {}",
            request.repository.name, request.before, request.after, request.commits.size,
        )
    }

    private fun createCodeReviewRecord(request: GithubPushRequest, targetApplication: String, compareResult: GitCompareResult, analyzeResults: String): CodeReviewRecord {
        val repoUrl = if (request.isNewBranch()) {
            "${request.repository.htmlUrl}/commit/${request.after}"
        } else {
            "${request.repository.htmlUrl}/compare/${request.before}...${request.after}"
        }
        val latestPushedAt = request.commits.mapNotNull { it.timestamp.toISO8601() }.maxOrNull() ?: LocalDateTime.now()
        val changedFiles = compareResult.changedFiles()
        val commitSummaries = compareResult.commitSummaries()
            .takeIf { it.isNotEmpty() }
            ?: request.commits.map { CommitSummary(it.id, it.message, it.url, it.timestamp) }
        val latestCommitMessage = commitSummaries.lastOrNull()?.message ?: ""
        val branch = request.ref.removePrefix("refs/heads/").takeIf { it.isNotBlank() }
        return CodeReviewRecord(
            latestPushedAt, targetApplication, repoUrl, latestCommitMessage,
            changedFiles, analyzeResults, LocalDateTime.now(), commitSummaries, branch,
        )
    }

    fun analyzePullRequest(request: GithubPullRequestRequest, application: String?) {
        recordPullRequestAuditLog(request)
        if (isSkippableAction(request)) {
            return
        }
        runCatching {
            val targetApplication = application ?: "Unknown Application"
            applicationService.addApp(AppUpdateRequest(targetApplication))
            val gitService = gitRemoteResolver.detectProviderService(request.source)

            if (!gitService.isTokenConfigured()) {
                log.warn("PR webhook received but {} token is not configured", request.source)
                return@runCatching
            }

            val resolvedRequest = gitService.resolveMissingPullRequestRefs(request)
            if (hasInvalidRefs(resolvedRequest)) {
                return@runCatching
            }

            val inquiry = GitDifferInquiry.of(resolvedRequest)
            val compareResult = gitService.executeInquiryDiffer(inquiry)
            if (compareResult.hasError()) {
                log.warn("PR diff fetch failed — skipping comment (number={}, error={})", resolvedRequest.number, compareResult.errorMessage)
                return@runCatching
            }
            when (resolvedRequest.action) {
                PullRequestAction.SYNCHRONIZE -> postInlineReview(gitService, inquiry, compareResult, resolvedRequest)
                else -> postSummaryReview(gitService, inquiry, compareResult, resolvedRequest)
            }
        }.onFailure { log.error("Failed to analyze pull request: {}", it.message, it) }
    }

    private fun postSummaryReview(gitService: GitRemoteService, inquiry: GitDifferInquiry, compareResult: GitCompareResult, request: GithubPullRequestRequest) {
        val reviewResult = aiModelService.executeAnalyzeCodeDiffer(compareResult.createCodeReviewPrompt())
        if (reviewResult.isBlank()) {
            log.warn("PR review result is blank — skipping comment (number={})", request.number)
            return
        }
        gitService.postPullRequestComment(inquiry, request.number, reviewResult)
    }

    private fun postInlineReview(gitService: GitRemoteService, inquiry: GitDifferInquiry, compareResult: GitCompareResult, request: GithubPullRequestRequest) {
        val deltaCompareResult = fetchDeltaCompare(gitService, inquiry, request, compareResult)
        val raw = aiModelService.executeAnalyzeCodeDifferInline(deltaCompareResult.createCodeReviewPrompt())
        if (raw.isBlank()) {
            log.warn("PR inline review result is blank — skipping (number={})", request.number)
            return
        }
        val review = inlineReviewParser.parse(raw)
        if (review.comments.isEmpty()) {
            log.info("PR inline review — LLM returned no inline comments, posting summary only (number={})", request.number)
            postSummaryFallback(gitService, inquiry, request.number, review.summary)
            return
        }
        val posted = gitService.postPullRequestInlineComments(inquiry, request.number, review, compareResult)
        if (!posted) {
            log.warn("PR inline review failed — falling back to summary comment (number={})", request.number)
            postSummaryFallback(gitService, inquiry, request.number, review.summary)
        }
    }

    private fun fetchDeltaCompare(gitService: GitRemoteService, inquiry: GitDifferInquiry, request: GithubPullRequestRequest, fullCompareResult: GitCompareResult): GitCompareResult {
        if (request.beforeSha.isBlank()) {
            log.info("PR inline review — beforeSha missing, reviewing full PR diff (number={})", request.number)
            return fullCompareResult
        }
        val deltaInquiry = inquiry.copy(base = request.beforeSha)
        val delta = gitService.executeInquiryDiffer(deltaInquiry)
        if (delta.hasError()) {
            log.warn("PR delta diff fetch failed — falling back to full PR diff (number={}, error={})", request.number, delta.errorMessage)
            return fullCompareResult
        }
        return delta
    }

    private fun postSummaryFallback(gitService: GitRemoteService, inquiry: GitDifferInquiry, number: Int, summary: String) {
        if (summary.isBlank()) {
            log.warn("PR summary fallback — blank summary, skipping (number={})", number)
            return
        }
        gitService.postPullRequestComment(inquiry, number, summary)
    }

    private fun recordPullRequestAuditLog(request: GithubPullRequestRequest) {
        log.info(
            "PR webhook received — provider: {}, repo: {}, number: {}, action: {}, draft: {}, base: {}, head: {}",
            request.source, request.repository.name, request.number, request.action, request.draft, request.baseSha, request.headSha,
        )
    }

    private fun isSkippableAction(request: GithubPullRequestRequest): Boolean {
        if (request.action == PullRequestAction.IGNORED) {
            log.warn("PR webhook skipped — action is not a review trigger (title='{}', number={})", request.title, request.number)
            return true
        }
        if (request.action == PullRequestAction.OPENED && request.draft) {
            log.warn("PR webhook skipped — draft PR (title='{}', number={})", request.title, request.number)
            return true
        }
        if (request.number <= 0) {
            log.warn("PR webhook skipped — missing MR number (number={})", request.number)
            return true
        }
        return false
    }

    private fun hasInvalidRefs(request: GithubPullRequestRequest): Boolean {
        if (request.baseSha.isBlank() || request.headSha.isBlank()) {
            log.warn("PR webhook skipped — missing base/head SHA after fallback (number={}, base={}, head={})", request.number, request.baseSha, request.headSha)
            return true
        }
        return false
    }
}

