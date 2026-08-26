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
import com.walter.spring.ai.ops.service.dto.GitRepositoryCoordinates
import com.walter.spring.ai.ops.util.GitRemoteResolver
import com.walter.spring.ai.ops.util.InlineReviewParser
import com.walter.spring.ai.ops.util.extension.toISO8601
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.Duration
import com.walter.spring.ai.ops.controller.dto.GitCommitHistoryEntry
import com.walter.spring.ai.ops.controller.dto.ManualCodeReviewRequest

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

    fun getBranches(application: String): List<String> {
        val context = resolveGit(application)
        return context.service.getBranches(context.coordinates.owner, context.coordinates.repo)
    }

    fun getCommitHistory(application: String, branch: String): List<GitCommitHistoryEntry> {
        val context = resolveGit(application)
        return context.service.getCommitHistory(context.coordinates.owner, context.coordinates.repo, branch)
    }

    fun validateManualReview(application: String, request: ManualCodeReviewRequest) {
        require(request.commitShas.isNotEmpty()) { "Select at least one commit." }
        require(request.commitShas.distinct().size == request.commitShas.size) { "Duplicate commits are not allowed." }
        val history = getCommitHistory(application, request.branch)
        val selectedIndexes = request.commitShas.map { sha ->
            history.indexOfFirst { it.sha == sha }.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("The selected commit is not available in the current branch history.")
        }.sorted()
        require(selectedIndexes.zipWithNext().all { (left, right) -> right == left + 1 }) {
            "Selected commits must be consecutive."
        }
        val dates = selectedIndexes.map { history[it].instant() }
        require(Duration.between(dates.min(), dates.max()).abs() <= Duration.ofDays(7)) {
            "The selected commits must be within seven days of each other."
        }
    }

    fun analyzeManualReview(application: String, request: ManualCodeReviewRequest) {
        runCatching {
            val context = resolveGit(application)
            val selection = selectCommits(context, request)
            val inquiry = GitDifferInquiry(context.coordinates.owner, context.coordinates.repo, selection.oldest.sha, selection.newest.sha, request.commitShas)
            val compareResult = context.service.executeInquiryDiffer(inquiry)
            require(!compareResult.hasError()) { compareResult.errorMessage }
            val reviewResult = aiModelService.executeAnalyzeCodeDiffer(compareResult.createCodeReviewPrompt())
            val record = createManualReviewRecord(application, request.branch, context.gitUrl, selection, compareResult, reviewResult)
            publishReview(context.service, record, application)
        }.onFailure { log.error("Failed to analyze manually selected commits: application={}, error={}", application, it.message, it) }
    }

    private data class GitContext(
        val gitUrl: String,
        val coordinates: GitRepositoryCoordinates,
        val service: GitRemoteService,
    )

    private data class CommitSelection(val ordered: List<GitCommitHistoryEntry>) {
        val oldest: GitCommitHistoryEntry get() = ordered.first()
        val newest: GitCommitHistoryEntry get() = ordered.last()
        fun compareUrl(gitUrl: String): String =
            if (ordered.size == 1) newest.url else "$gitUrl/compare/${oldest.sha}...${newest.sha}"
    }

    private fun resolveGit(application: String): GitContext {
        val gitUrl = applicationService.getGitRepoByAppName(application)
        val coordinates = gitRemoteResolver.parseCoordinates(gitUrl)
        val service = gitRemoteResolver.detectProviderServiceByUrl(gitUrl)
        return GitContext(gitUrl, coordinates, service)
    }

    private fun selectCommits(context: GitContext, request: ManualCodeReviewRequest): CommitSelection {
        val history = context.service.getCommitHistory(context.coordinates.owner, context.coordinates.repo, request.branch)
        val ordered = request.commitShas
            .map { sha -> history.first { it.sha == sha } }
            .sortedBy { it.instant() }
        return CommitSelection(ordered)
    }

    private fun createManualReviewRecord(
        application: String,
        branch: String,
        gitUrl: String,
        selection: CommitSelection,
        compareResult: GitCompareResult,
        reviewResult: String,
    ): CodeReviewRecord {
        val summaries = selection.ordered.map { CommitSummary(it.sha, it.title, it.url, it.committedAt) }
        return CodeReviewRecord(
            OffsetDateTime.parse(selection.newest.committedAt).toLocalDateTime(),
            application,
            selection.compareUrl(gitUrl),
            selection.newest.title,
            compareResult.changedFiles(),
            reviewResult,
            LocalDateTime.now(),
            summaries,
            branch,
        )
    }

    private fun publishReview(gitService: GitRemoteService, record: CodeReviewRecord, application: String) {
        gitService.saveCodeReviewRecord(record)
        messageService.pushCodeReview(record)
        eventPublisher.publishEvent(CodeReviewCompletedEvent(record, application))
    }

    private fun GitCommitHistoryEntry.instant() = OffsetDateTime.parse(committedAt).toInstant()

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
            publishReview(gitService, record, targetApplication)
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
