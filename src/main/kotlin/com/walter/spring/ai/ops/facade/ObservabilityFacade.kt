package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.code.GitRemoteProvider
import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.connector.dto.GitCompareResult
import com.walter.spring.ai.ops.connector.dto.GitDifferInquiry
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.LokiQueryResult
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest
import com.walter.spring.ai.ops.controller.dto.GithubPushRequest
import com.walter.spring.ai.ops.record.AnalyzeFiringRecord
import com.walter.spring.ai.ops.record.CodeReviewRecord
import com.walter.spring.ai.ops.record.CommitSummary
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.GitlabService
import com.walter.spring.ai.ops.service.GitRemoteService
import com.walter.spring.ai.ops.service.GrafanaService
import com.walter.spring.ai.ops.service.LokiService
import com.walter.spring.ai.ops.service.MessageService
import com.walter.spring.ai.ops.util.toISO8601
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

@Facade
class ObservabilityFacade(
    private val applicationService: ApplicationService,
    private val grafanaService: GrafanaService,
    private val lokiService: LokiService,
    private val githubService: GithubService,
    private val gitlabService: GitlabService,
    private val aiModelService: AiModelService,
    private val messageService: MessageService,
) {
    private val log = LoggerFactory.getLogger(ObservabilityFacade::class.java)

    private fun resolveGitServiceBySource(source: GitRemoteProvider): GitRemoteService = when (source) {
        GitRemoteProvider.GITHUB -> githubService
        GitRemoteProvider.GITLAB -> gitlabService
    }

    fun analyzeFiring(request: GrafanaAlertingRequest, application: String?) {
        if (request.isResolved()) {
            return
        }
        recordAuditLog(request)
        runCatching {
            val targetApplication = application ?: "Unknown Application"
            applicationService.addApp(targetApplication)
            val logQuery = grafanaService.convertLogInquiry(request)
            val logResults = lokiService.executeLogQuery(logQuery)
            val analyzeResults = aiModelService.executeAnalyzeFiring(request.createAlertSectionPrompt(), logResults.createLogSectionPrompt())
            val record = createAnalyzeFiringRecord(request, targetApplication, logResults, analyzeResults)
            grafanaService.saveAnalyzeFiringRecord(record)
            messageService.pushFiring(record)
        }.onFailure { log.error("Failed to analyze Firing : {}", it.message, it) }
    }

    private fun recordAuditLog(request: GrafanaAlertingRequest) {
        log.info("Grafana webhook received — status: {}, alerts: {}, title: {}", request.status, request.alerts.size, request.title)
        request.alerts.filter { it.isFiring() }.forEach { alert ->
            log.info("Firing alert — name: {}, fingerprint: {}, lokiLabels: {}, startsAt: {}", alert.alertName(), alert.fingerprint, alert.lokiLabels(), alert.startsAt)
        }
    }

    private fun createAnalyzeFiringRecord(request: GrafanaAlertingRequest, targetApplication: String, logResults: LokiQueryResult, analyzeResults: String): AnalyzeFiringRecord {
        return AnalyzeFiringRecord(
            request.alerts.first().startsAt.toISO8601(),
            targetApplication,
            request,
            logResults,
            analyzeResults,
            LocalDateTime.now(),
        )
    }

    fun analyzeCodeDiffer(request: GithubPushRequest, application: String?) {
        if (request.commits.isEmpty()) {
            log.warn("Git push webhook skipped — no commits (ping or empty push)")
            return
        }
        recordAuditLog(request)
        runCatching {
            val targetApplication = application ?: "Unknown Application"
            applicationService.addApp(targetApplication)
            val gitService = resolveGitServiceBySource(request.source)

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
        }.onFailure { log.error("Failed to analyze code review record : {}", it.message, it) }
    }

    private fun recordAuditLog(request: GithubPushRequest) {
        log.info("Git push webhook received — repo: {}, before: {}, after: {}, commits: {}", request.repository.name, request.before, request.after, request.commits.size)
    }

    private fun createCodeReviewRecord(request: GithubPushRequest, targetApplication: String, compareResult: GitCompareResult, analyzeResults: String): CodeReviewRecord {
        val repoUrl = if (request.isNewBranch())
            "${request.repository.htmlUrl}/commit/${request.after}"
        else
            "${request.repository.htmlUrl}/compare/${request.before}...${request.after}"
        val latestPushedAt = request.commits.mapNotNull { it.timestamp.toISO8601() }.maxOrNull() ?: LocalDateTime.now()
        val changedFiles = compareResult.changedFiles()
        val commitSummaries = compareResult.commitSummaries()
            .takeIf { it.isNotEmpty() }
            ?: request.commits.map { CommitSummary(it.id, it.message, it.url, it.timestamp) }
        val latestCommitMessage = commitSummaries.lastOrNull()?.message ?: ""
        val branch = request.ref.removePrefix("refs/heads/").takeIf { it.isNotBlank() }
        return CodeReviewRecord(latestPushedAt, targetApplication, repoUrl, latestCommitMessage, changedFiles, analyzeResults, LocalDateTime.now(), commitSummaries, branch)
    }
}
