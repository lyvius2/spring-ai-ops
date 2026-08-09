package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.code.PullRequestAction
import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.config.exception.InvalidPullRequestException
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
        validateRequestAction(request)
        runCatching {
            val targetApplication = application ?: "Unknown Application"
            applicationService.addApp(AppUpdateRequest(targetApplication))
            val gitService = gitRemoteResolver.detectProviderService(request.source)

            if (!gitService.isTokenConfigured()) {
                log.warn("PR webhook received but {} token is not configured", request.source)
                return@runCatching
            }

            val inquiry = GitDifferInquiry.of(request)
            val compareResult = gitService.executeInquiryDiffer(inquiry)
            if (compareResult.hasError()) {
                log.warn("PR diff fetch failed — skipping comment (number={}, error={})", request.number, compareResult.errorMessage)
                return@runCatching
            }
            when (request.action) {
                PullRequestAction.SYNCHRONIZE -> postInlineReview(gitService, inquiry, compareResult, request)
                else -> postSummaryReview(gitService, inquiry, compareResult, request)
            }
        }.onFailure { log.error("Failed to analyze pull request: {}", it.message, it) }
    }

    private fun postSummaryReview(gitService: GitRemoteService, inquiry: GitDifferInquiry, compareResult: GitCompareResult, request: GithubPullRequestRequest) {
        val reviewResult = aiModelService.executeAnalyzeCodeDiffer(compareResult.createCodeReviewPrompt())
        if (reviewResult.isBlank()) {
            log.warn("PR review result is blank — skipping comment (number={})", request.number)
            return
        }
        gitService.postPullRequestComment(inquiry, request.number, formatPullRequestComment(reviewResult))
    }

    private fun postInlineReview(gitService: GitRemoteService, inquiry: GitDifferInquiry, compareResult: GitCompareResult, request: GithubPullRequestRequest) {
        val raw = aiModelService.executeAnalyzeCodeDifferInline(compareResult.createCodeReviewPrompt())
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

    private fun postSummaryFallback(gitService: GitRemoteService, inquiry: GitDifferInquiry, number: Int, summary: String) {
        if (summary.isBlank()) {
            log.warn("PR summary fallback — blank summary, skipping (number={})", number)
            return
        }
        gitService.postPullRequestComment(inquiry, number, formatPullRequestComment(summary))
    }

    private fun recordPullRequestAuditLog(request: GithubPullRequestRequest) {
        log.info(
            "PR webhook received — provider: {}, repo: {}, number: {}, action: {}, draft: {}, base: {}, head: {}",
            request.source, request.repository.name, request.number, request.action, request.draft, request.baseSha, request.headSha,
        )
    }

    private fun validateRequestAction(request: GithubPullRequestRequest) {
        if (request.action == PullRequestAction.IGNORED) {
            throw InvalidPullRequestException("action is not a review trigger (title='${request.title}', number=${request.number})")
        }
        if (request.action == PullRequestAction.OPENED && request.draft) {
            throw InvalidPullRequestException("draft PR (title='${request.title}', number=${request.number})")
        }
        if (request.number <= 0 || request.baseSha.isBlank() || request.headSha.isBlank()) {
            throw InvalidPullRequestException("missing number/base/head (number=${request.number})")
        }
    }

    private fun formatPullRequestComment(reviewMarkdown: String): String = buildString {
        appendLine("## AI Code Review")
        appendLine()
        appendLine(reviewMarkdown.trim())
        appendLine()
        appendLine("---")
        appendLine("_Automated review by Spring AIOps._")
    }
}

