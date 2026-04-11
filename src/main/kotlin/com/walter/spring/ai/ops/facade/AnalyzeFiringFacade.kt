package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.code.AlertingStatus
import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.connector.dto.LokiQueryResult
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest
import com.walter.spring.ai.ops.record.AnalyzeFiringRecord
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GrafanaService
import com.walter.spring.ai.ops.service.LokiService
import com.walter.spring.ai.ops.util.toISO8601
import org.springframework.beans.factory.annotation.Qualifier
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@Facade
class AnalyzeFiringFacade(
    private val applicationService: ApplicationService,
    private val grafanaService: GrafanaService,
    private val lokiService: LokiService,
    private val aiModelService: AiModelService,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {
    fun process(request: GrafanaAlertingRequest, targetApplication: String): AlertingStatus {
        if (request.isResolved()) {
            return AlertingStatus.RESOLVED
        }
        applicationService.addApp(targetApplication)
        val logQuery = grafanaService.convertLogInquiry(request)
        val logResults = lokiService.executeLogQuery(logQuery)
        val analyzeResults = aiModelService.executeAnalyzeFiring(request.createAlertSectionPrompt(), logResults.createLogSectionPrompt())
        putAnalyzeFiring(request, targetApplication, logResults, analyzeResults)
        return AlertingStatus.FIRING
    }

    private fun putAnalyzeFiring(request: GrafanaAlertingRequest, targetApplication: String, logResults: LokiQueryResult, analyzeResults: String) {
        CompletableFuture.runAsync(
            {
                val record = AnalyzeFiringRecord(request.alerts.first().startsAt.toISO8601(),
                    targetApplication,
                    request,
                    logResults,
                    analyzeResults,
                    LocalDateTime.now()
                )
                grafanaService.saveAnalyzeFiringRecord(targetApplication, record)
            },
            executor
        )
    }
}
