package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.code.GitRemoteProvider
import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.connector.dto.GitCompareResult
import com.walter.spring.ai.ops.controller.dto.AppUpdateRequest
import com.walter.spring.ai.ops.connector.dto.GitDifferInquiry
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.LokiQueryResult
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryResult
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest
import com.walter.spring.ai.ops.controller.dto.GithubPushRequest
import com.walter.spring.ai.ops.record.AnalyzeFiringRecord
import com.walter.spring.ai.ops.record.CodeReviewRecord
import com.walter.spring.ai.ops.record.CommitSummary
import com.walter.spring.ai.ops.record.SourceCodeSuggestion
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.GitlabService
import com.walter.spring.ai.ops.service.GitRemoteService
import com.walter.spring.ai.ops.service.GrafanaService
import com.walter.spring.ai.ops.service.IncidentSourceContextService
import com.walter.spring.ai.ops.service.LokiService
import com.walter.spring.ai.ops.service.MessageService
import com.walter.spring.ai.ops.service.PrometheusService
import com.walter.spring.ai.ops.service.RepositoryService
import com.walter.spring.ai.ops.util.CodeAnalysisResultHandler
import com.walter.spring.ai.ops.util.extension.toISO8601
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@Facade
class ObservabilityFacade(
    private val applicationService: ApplicationService,
    private val grafanaService: GrafanaService,
    private val lokiService: LokiService,
    private val prometheusService: PrometheusService,
    private val githubService: GithubService,
    private val gitlabService: GitlabService,
    private val aiModelService: AiModelService,
    private val repositoryService: RepositoryService,
    private val incidentSourceContextService: IncidentSourceContextService,
    private val messageService: MessageService,
    private val codeAnalysisResultHandler: CodeAnalysisResultHandler,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {
    private val log = LoggerFactory.getLogger(ObservabilityFacade::class.java)

    companion object {
        private const val SOURCE_CODE_SUGGESTIONS_START = "---SOURCE_CODE_SUGGESTIONS_JSON_START---"
        private const val SOURCE_CODE_SUGGESTIONS_END = "---SOURCE_CODE_SUGGESTIONS_JSON_END---"
    }

    private fun resolveGitServiceBySource(source: GitRemoteProvider): GitRemoteService = when (source) {
        GitRemoteProvider.GITHUB -> githubService
        GitRemoteProvider.GITLAB -> gitlabService
    }

    fun analyzeFiring(request: GrafanaAlertingRequest, application: String?) {
        if (request.isResolved()) {
            return
        }
        log.info("Grafana webhook received — status: {}, alerts: {}, title: {}", request.status, request.alerts.size, request.title)
        runCatching {
            val targetApplication = application ?: "Unknown Application"
            applicationService.addApp(AppUpdateRequest(targetApplication))

            val logFuture = CompletableFuture.supplyAsync({ executeFindLog(request) }, executor)
            val metricFuture = CompletableFuture.supplyAsync({ executeFindMetric(request) }, executor)
            val checkoutFuture = CompletableFuture.supplyAsync({ getSourcePath(targetApplication) }, executor)

            val logResults: LokiQueryResult = logFuture.get()
            val metricResults: PrometheusQueryResult? = metricFuture.get()
            val sourcePath: Path? = checkoutFuture.get()
            val sourceContext = incidentSourceContextService.createContext(logResults, sourcePath)
            val sourceSection = sourceContext.createSourceSectionPrompt()

            val rawAnalyzeResults = aiModelService.executeAnalyzeFiring(
                request.createAlertSectionPrompt(),
                logResults.createLogSectionPrompt(),
                metricResults?.createMetricSectionPrompt() ?: "",
                sourceSection,
            )
            val (analyzeResults, sourceCodeSuggestions) = parseAnalyzeFiringResponse(rawAnalyzeResults)
            val record = createAnalyzeFiringRecord(request, targetApplication, logResults, metricResults, analyzeResults, sourceCodeSuggestions)
            grafanaService.saveAnalyzeFiringRecord(record)
            messageService.pushFiring(record)
        }.onFailure { log.error("Failed to analyze Firing : {}", it.message, it) }
    }

    private fun executeFindLog(request: GrafanaAlertingRequest): LokiQueryResult {
        val lokiQueryRequest = grafanaService.convertLogInquiry(request)
        return lokiService.executeLogQuery(lokiQueryRequest)
    }

    private fun executeFindMetric(request: GrafanaAlertingRequest): PrometheusQueryResult? =
        if (prometheusService.isConfigured()) {
            prometheusService.executeMetricQuery(grafanaService.convertMetricInquiry(request))
        } else {
            null
        }

    private fun getSourcePath(targetApplication: String): Path? {
        val appConfig = applicationService.getAppConfig(targetApplication)
        return if (appConfig != null && appConfig.isValidConfig()) {
            val accessToken = resolveAccessToken(appConfig.gitUrl!!)
            repositoryService.prepareRepository(
                appName = targetApplication,
                gitUrl = appConfig.gitUrl,
                branch = appConfig.deployBranch!!,
                accessToken = accessToken,
            )
        } else {
            null
        }
    }

    private fun resolveAccessToken(gitUrl: String): String? {
        val lower = gitUrl.lowercase()
        return when {
            lower.contains("github") -> githubService.getToken()
            lower.contains("gitlab") -> gitlabService.getToken()
            else -> null
        }
    }

    private fun createAnalyzeFiringRecord(
        request: GrafanaAlertingRequest,
        targetApplication: String,
        logResults: LokiQueryResult,
        metricResults: PrometheusQueryResult?,
        analyzeResults: String,
        sourceCodeSuggestions: List<SourceCodeSuggestion>,
    ): AnalyzeFiringRecord {
        return AnalyzeFiringRecord(
            request.alerts.first().startsAt.toISO8601(),
            targetApplication,
            request,
            logResults,
            metricResults,
            analyzeResults,
            sourceCodeSuggestions,
            LocalDateTime.now(),
        )
    }

    private fun parseAnalyzeFiringResponse(raw: String): Pair<String, List<SourceCodeSuggestion>> {
        val startIdx = raw.indexOf(SOURCE_CODE_SUGGESTIONS_START)
        if (startIdx == -1) {
            return Pair(raw.trim(), emptyList())
        }

        val markdown = raw.substring(0, startIdx).trim()
        val afterStart = raw.substring(startIdx + SOURCE_CODE_SUGGESTIONS_START.length)
        val endIdx = afterStart.indexOf(SOURCE_CODE_SUGGESTIONS_END)
        val jsonText = (if (endIdx == -1) afterStart else afterStart.substring(0, endIdx)).trim()
        val sanitized = codeAnalysisResultHandler.sanitizeControlChars(jsonText)

        val suggestions = runCatching {
            codeAnalysisResultHandler.parseJsonArray(sanitized, SourceCodeSuggestion::class.java)
        }.getOrElse {
            log.warn("Failed to parse source code suggestions JSON — attempting recovery: {}", it.message)
            codeAnalysisResultHandler.recoverIssuesFromJson(sanitized, SourceCodeSuggestion::class.java)
        }
        return Pair(markdown, suggestions)
    }

    fun analyzeCodeDiffer(request: GithubPushRequest, application: String?) {
        if (request.commits.isEmpty()) {
            log.warn("Git push webhook skipped — no commits (ping or empty push)")
            return
        }
        recordAuditLog(request)
        runCatching {
            val targetApplication = application ?: "Unknown Application"
            applicationService.addApp(AppUpdateRequest(targetApplication))
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
