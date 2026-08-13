package com.walter.spring.ai.ops.record;

import com.walter.spring.ai.ops.code.AnalysisStatus;
import com.walter.spring.ai.ops.connector.dto.LokiQueryResult;
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryResult;
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.commons.lang3.ObjectUtils;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

@Schema(description = "Grafana alert firing analysis record persisted after LLM analysis")
public record AnalyzeFiringRecord(
    @Schema(description = "Timestamp when the alert was received and analysis started")
    LocalDateTime occupiedAt,
    @Schema(description = "Application name derived from alert labels")
    String application,
    @Schema(description = "Original Grafana Alerting webhook payload")
    GrafanaAlertingRequest alertingMessage,
    @Schema(description = "Loki log query result used for analysis")
    LokiQueryResult log,
    @Schema(description = "Prometheus metric query result used for analysis; null if Prometheus is not configured")
    PrometheusQueryResult metrics,
    @Schema(description = "LLM-generated analysis result in Markdown; blank when analysis was skipped")
    String analyzeResults,
    @Schema(description = "AI-generated source code change suggestions related to the incident")
    List<SourceCodeSuggestion> sourceCodeSuggestions,
    @Schema(description = "Timestamp when the analysis was completed")
    LocalDateTime completedAt,
    @Schema(description = "Outcome of the analysis pipeline for this alert")
    AnalysisStatus analysisStatus,
    @Schema(description = "Human-readable explanation when analysis was skipped; blank when analysis completed normally")
    String analysisMessage
) {
    public static AnalyzeFiringRecord create(GrafanaAlertingRequest request,
                                             String targetApplication,
                                             LokiQueryResult logResults,
                                             PrometheusQueryResult metricResults,
                                             String analyzeResults,
                                             List<SourceCodeSuggestion> sourceCodeSuggestions) {
        return new AnalyzeFiringRecord(
            resolveOccupiedAt(request),
            targetApplication,
            request,
            logResults,
            metricResults,
            analyzeResults,
            sourceCodeSuggestions,
            LocalDateTime.now(),
            AnalysisStatus.ANALYZED,
            ""
        );
    }

    public static AnalyzeFiringRecord createSkipped(GrafanaAlertingRequest request,
                                                    String targetApplication,
                                                    AnalysisStatus status,
                                                    String message,
                                                    LokiQueryResult logResults,
                                                    PrometheusQueryResult metricResults) {
        return new AnalyzeFiringRecord(
            resolveOccupiedAt(request),
            targetApplication,
            request,
            logResults,
            metricResults,
            "",
            Collections.emptyList(),
            LocalDateTime.now(),
            status,
            message
        );
    }

    private static LocalDateTime resolveOccupiedAt(GrafanaAlertingRequest request) {
        try {
            if (ObjectUtils.allNotNull(request, request.getAlerts(), request.getAlerts().getFirst(), request.getAlerts().getFirst().getStartsAt())) {
                return OffsetDateTime.parse(request.getAlerts().getFirst().getStartsAt()).toLocalDateTime();
            }
        } catch (Exception ignored) { }
        return LocalDateTime.now();
    }
}