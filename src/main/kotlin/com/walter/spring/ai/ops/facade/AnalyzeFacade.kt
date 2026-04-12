package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.GithubDifferInquiry
import com.walter.spring.ai.ops.connector.dto.LokiQueryResult
import com.walter.spring.ai.ops.record.ChangedFile
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest
import com.walter.spring.ai.ops.controller.dto.GithubPushRequest
import com.walter.spring.ai.ops.record.AnalyzeFiringRecord
import com.walter.spring.ai.ops.record.CodeReviewRecord
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.GrafanaService
import com.walter.spring.ai.ops.service.LokiService
import com.walter.spring.ai.ops.util.toISO8601
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.time.LocalDateTime

@Facade
class AnalyzeFacade(
    private val applicationService: ApplicationService,
    private val grafanaService: GrafanaService,
    private val lokiService: LokiService,
    private val githubService: GithubService,
    private val aiModelService: AiModelService,
    private val messagingTemplate: SimpMessagingTemplate,
) {
    private val log = LoggerFactory.getLogger(AnalyzeFacade::class.java)

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
            pushAnalyzeFiring(record)
        }.onFailure { log.error("Failed to analyze Firing : {}", it.message, it) }
    }

    private fun recordAuditLog(request: GrafanaAlertingRequest) {
        log.info("Grafana webhook received — status: {}, alerts: {}, title: {}", request.status, request.alerts.size, request.title)
        request.alerts.filter { it.isFiring() }.forEach { alert ->
            log.info("Firing alert — name: {}, fingerprint: {}, lokiLabels: {}, startsAt: {}", alert.alertName(), alert.fingerprint, alert.lokiLabels(), alert.startsAt)
        }
    }

    private fun createAnalyzeFiringRecord(request: GrafanaAlertingRequest, targetApplication: String, logResults: LokiQueryResult, analyzeResults: String): AnalyzeFiringRecord {
        return AnalyzeFiringRecord(request.alerts.first().startsAt.toISO8601(),
            targetApplication,
            request,
            logResults,
            analyzeResults,
            LocalDateTime.now()
        )
    }

    private fun pushAnalyzeFiring(record: AnalyzeFiringRecord) {
        messagingTemplate.convertAndSend("/topic/firing", record)
    }

    fun analyzeCodeDiffer(request: GithubPushRequest, application: String?) {
        recordAuditLog(request)
        runCatching {
            val targetApplication = application ?: "Unknown Application"
            applicationService.addApp(targetApplication)
            val inquiry = GithubDifferInquiry.of(request)
            val compareResult = githubService.executeInquiryDiffer(inquiry)
            val reviewResult = aiModelService.executeAnalyzeCodeDiffer(compareResult.createCodeReviewPrompt())
            val record = createCodeReviewRecord(request, targetApplication, compareResult, reviewResult)
            githubService.saveCodeReviewRecord(record)
            // TODO: websocket push
        }.onFailure { log.error("Failed to analyze code review record : {}", it.message, it) }
    }

    private fun recordAuditLog(request: GithubPushRequest) {
        log.info("GitHub push webhook received — repo: {}, before: {}, after: {}, commits: {}", request.repository.name, request.before, request.after, request.commits.size,)
    }

    private fun createCodeReviewRecord(request: GithubPushRequest, targetApplication: String, compareResult: GithubCompareResult, analyzeResults: String): CodeReviewRecord {
        val githubUrl = "${request.repository.htmlUrl}/commit/${request.after}"
        val headCommit = request.commits.firstOrNull()
        val changedFiles = compareResult.files.map { file ->
            ChangedFile(file.filename, file.status, file.additions, file.deletions, file.patch)
        }
        return CodeReviewRecord(
            headCommit?.timestamp?.toISO8601() ?: LocalDateTime.now(),
            targetApplication,
            githubUrl,
            headCommit?.message ?: "",
            changedFiles,
            analyzeResults,
            LocalDateTime.now()
        )
    }
}
