package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.code.AnalysisStatus
import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.controller.dto.AppUpdateRequest
import com.walter.spring.ai.ops.connector.dto.LokiQueryResult
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryResult
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest
import com.walter.spring.ai.ops.record.AnalyzeFiringRecord
import com.walter.spring.ai.ops.record.SourceCodeSuggestion
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GrafanaService
import com.walter.spring.ai.ops.service.IncidentSourceContextService
import com.walter.spring.ai.ops.service.LokiService
import com.walter.spring.ai.ops.service.MessageService
import com.walter.spring.ai.ops.service.PrometheusService
import com.walter.spring.ai.ops.service.RepositoryService
import com.walter.spring.ai.ops.util.CodeAnalysisResultHandler
import com.walter.spring.ai.ops.util.GitRemoteResolver
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@Facade
class IncidentAnalyzeFacade(
    private val applicationService: ApplicationService,
    private val grafanaService: GrafanaService,
    private val lokiService: LokiService,
    private val prometheusService: PrometheusService,
    private val gitRemoteResolver: GitRemoteResolver,
    private val aiModelService: AiModelService,
    private val repositoryService: RepositoryService,
    private val incidentSourceContextService: IncidentSourceContextService,
    private val messageService: MessageService,
    private val codeAnalysisResultHandler: CodeAnalysisResultHandler,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {
    private val log = LoggerFactory.getLogger(IncidentAnalyzeFacade::class.java)

    companion object {
        private const val SOURCE_CODE_SUGGESTIONS_START = "---SOURCE_CODE_SUGGESTIONS_JSON_START---"
        private const val SOURCE_CODE_SUGGESTIONS_END = "---SOURCE_CODE_SUGGESTIONS_JSON_END---"
    }

    fun analyzeFiring(request: GrafanaAlertingRequest, application: String?) {
        if (request.isResolved()) {
            return
        }
        log.info("Grafana webhook received — status: {}, alerts: {}, title: {}", request.status, request.alerts.size, request.title)
        runCatching {
            val targetApplication = application ?: "Unknown Application"
            applicationService.addApp(AppUpdateRequest(targetApplication))

            val lokiConfigured = lokiService.isConfigured()
            val prometheusConfigured = prometheusService.isConfigured()

            if (!lokiConfigured && !prometheusConfigured) {
                persistAndPush(
                    AnalyzeFiringRecord.createSkipped(
                        request,
                        targetApplication,
                        AnalysisStatus.SKIPPED_NO_OBSERVABILITY,
                        "Loki and Prometheus are not configured. The alert was recorded, but no logs or metrics were collected and LLM analysis was skipped.",
                        null,
                        null,
                    )
                )
                return@runCatching
            }

            val logFuture = if (lokiConfigured) {
                CompletableFuture.supplyAsync({ executeFindLog(request) }, executor)
            } else null
            val metricFuture = if (prometheusConfigured) {
                CompletableFuture.supplyAsync({ executeFindMetric(request) }, executor)
            } else null
            val checkoutFuture = CompletableFuture.supplyAsync({ getSourcePath(targetApplication) }, executor)

            val logResults: LokiQueryResult? = logFuture?.get()
            val metricResults: PrometheusQueryResult? = metricFuture?.get()
            val sourcePath: Path? = checkoutFuture.get()

            val lokiFailed = lokiConfigured && !isLokiSuccessful(logResults)
            val prometheusFailed = prometheusConfigured && !isPrometheusSuccessful(metricResults)
            val everythingFailed = (!lokiConfigured || lokiFailed) && (!prometheusConfigured || prometheusFailed)

            if (everythingFailed) {
                val message = buildConnectionErrorMessage(lokiConfigured, prometheusConfigured, logResults, metricResults)
                persistAndPush(
                    AnalyzeFiringRecord.createSkipped(
                        request,
                        targetApplication,
                        AnalysisStatus.SKIPPED_OBSERVABILITY_ERROR,
                        message,
                        logResults,
                        metricResults,
                    )
                )
                return@runCatching
            }

            val logSection = if (!lokiFailed) logResults?.createLogSectionPrompt() ?: "" else ""
            val metricSection = if (!prometheusFailed) metricResults?.createMetricSectionPrompt() ?: "" else ""
            val sourceContext = incidentSourceContextService.createContext(logResults ?: LokiQueryResult(), sourcePath)
            val sourceSection = sourceContext.createSourceSectionPrompt()

            val rawAnalyzeResults = aiModelService.executeAnalyzeFiring(
                request.createAlertSectionPrompt(),
                logSection,
                metricSection,
                sourceSection,
            )
            val (analyzeResults, sourceCodeSuggestions) = parseAnalyzeFiringResponse(rawAnalyzeResults)
            val record = AnalyzeFiringRecord.create(request, targetApplication, logResults, metricResults, analyzeResults, sourceCodeSuggestions)
            persistAndPush(record)
        }.onFailure { log.error("Failed to analyze Firing : {}", it.message, it) }
    }

    private fun executeFindLog(request: GrafanaAlertingRequest): LokiQueryResult =
        runCatching { lokiService.executeLogQuery(grafanaService.convertLogInquiry(request)) }
            .getOrElse { LokiQueryResult(errorMessage = it.message ?: "Failed to query Loki.") }

    private fun executeFindMetric(request: GrafanaAlertingRequest): PrometheusQueryResult =
        runCatching { prometheusService.executeMetricQuery(grafanaService.convertMetricInquiry(request)) }
            .getOrElse { PrometheusQueryResult(errorMessage = it.message ?: "Failed to query Prometheus.") }

    private fun isLokiSuccessful(result: LokiQueryResult?): Boolean =
        result != null && result.errorMessage.isBlank()

    private fun isPrometheusSuccessful(result: PrometheusQueryResult?): Boolean =
        result != null && result.errorMessage.isBlank() && result.error.isBlank()

    private fun buildConnectionErrorMessage(lokiConfigured: Boolean, prometheusConfigured: Boolean, logResults: LokiQueryResult?, metricResults: PrometheusQueryResult?, ): String {
        val parts = mutableListOf<String>()
        if (lokiConfigured) {
            val detail = logResults?.errorMessage?.takeIf { it.isNotBlank() } ?: "connection error"
            parts.add("Loki: $detail")
        }
        if (prometheusConfigured) {
            val detail = metricResults?.errorMessage?.takeIf { it.isNotBlank() }
                ?: metricResults?.error?.takeIf { it.isNotBlank() }
                ?: "connection error"
            parts.add("Prometheus: $detail")
        }
        val detail = parts.joinToString(separator = "; ")
        return "Failed to fetch logs and metrics due to a connection error ($detail). The alert was recorded, but LLM analysis was skipped."
    }

    private fun persistAndPush(record: AnalyzeFiringRecord) {
        grafanaService.saveAnalyzeFiringRecord(record)
        messageService.pushFiring(record)
    }

    private fun getSourcePath(targetApplication: String): Path? {
        val appConfig = applicationService.getAppConfig(targetApplication)
        return if (appConfig != null && appConfig.isValidConfig()) {
            val accessToken = gitRemoteResolver.getToken(appConfig.gitUrl!!)
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
}