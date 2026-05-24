package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.connector.dto.GitCompareResult
import com.walter.spring.ai.ops.controller.dto.AppUpdateRequest
import com.walter.spring.ai.ops.connector.dto.GitDifferInquiry
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.controller.dto.GithubPushRequest
import com.walter.spring.ai.ops.event.CodeReviewCompletedEvent
import com.walter.spring.ai.ops.record.CodeReviewRecord
import com.walter.spring.ai.ops.record.CommitSummary
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.MessageService
import com.walter.spring.ai.ops.util.GitRemoteResolver
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
}

