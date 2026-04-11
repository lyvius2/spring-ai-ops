package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.code.AlertingStatus
import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.connector.dto.LokiQueryResult
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GrafanaService
import com.walter.spring.ai.ops.service.LokiService
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Facade
class FiringAnalyzeFacade(
    private val applicationService: ApplicationService,
    private val grafanaService: GrafanaService,
    private val lokiService: LokiService,
    private val aiModelService: AiModelService,
) {
    fun process(request: GrafanaAlertingRequest, targetApplication: String): AlertingStatus {
        if (request.isResolved()) {
            return AlertingStatus.RESOLVED
        }
        applicationService.addApp(targetApplication)
        val logQuery = grafanaService.convertLogInquiry(request)
        val logResults = lokiService.executeLogQuery(logQuery)
        val chatModel = aiModelService.getChatModel()
        analyze(request, logResults, chatModel)

        return AlertingStatus.FIRING
    }

    private fun analyze(
        request: GrafanaAlertingRequest,
        logResults: LokiQueryResult,
        chatModel: ChatModel,
    ): String {
        val alert = request.alerts.firstOrNull { it.isFiring() } ?: request.alerts.firstOrNull()

        val alertSection = buildString {
            appendLine("## Alert Information")
            appendLine("- Title: ${request.title}")
            appendLine("- Status: ${request.status}")
            appendLine("- Message: ${request.message}")
            if (alert != null) {
                appendLine("- Alert Name: ${alert.alertName()}")
                appendLine("- Started At: ${alert.startsAt}")
                if (alert.annotations.isNotEmpty()) {
                    appendLine("- Annotations: ${alert.annotations.entries.joinToString { "${it.key}=${it.value}" }}")
                }
                if (alert.labels.isNotEmpty()) {
                    appendLine("- Labels: ${alert.labels.entries.joinToString { "${it.key}=${it.value}" }}")
                }
            }
        }

        val logSection = buildString {
            appendLine("## Application Logs")
            val streams = logResults.data?.result ?: emptyList()
            if (streams.isEmpty() || streams.all { it.values.isEmpty() }) {
                appendLine("(No logs available for the given time range and label selector.)")
            } else {
                streams.forEach { stream ->
                    if (stream.stream.isNotEmpty()) {
                        appendLine("### Stream Labels: ${stream.stream.entries.joinToString { "${it.key}=${it.value}" }}")
                    }
                    stream.values.forEach { entry ->
                        val timestamp = entry.getOrNull(0)
                            ?.toLongOrNull()
                            ?.let { nanos ->
                                Instant.ofEpochMilli(nanos / 1_000_000)
                                    .atOffset(ZoneOffset.UTC)
                                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
                            } ?: ""
                        val logLine = entry.getOrNull(1) ?: ""
                        appendLine("[$timestamp] $logLine")
                    }
                }
            }
        }

        val systemMessage = SystemMessage(
            "You are an expert in analyzing application errors and logs. " +
            "Analyze the provided Grafana alert context and application logs, " +
            "identify the root cause, and give clear, actionable recommendations."
        )

        val userMessage = UserMessage(
            buildString {
                append(alertSection)
                appendLine()
                append(logSection)
                appendLine()
                appendLine("Based on the above alert and logs, please provide:")
                appendLine("1. Root cause analysis")
                appendLine("2. Affected components")
                appendLine("3. Recommended actions to resolve the issue")
            }
        )

        val response = chatModel.call(Prompt(listOf(systemMessage, userMessage)))
        return response.result.output.text ?: ""
    }
}
