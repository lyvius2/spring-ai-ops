package com.walter.spring.ai.ops.record;

import com.walter.spring.ai.ops.connector.dto.LokiQueryResult;
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryResult;
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.commons.lang3.ObjectUtils;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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
    @Schema(description = "LLM-generated analysis result in Markdown")
    String analyzeResults,
    @Schema(description = "AI-generated source code change suggestions related to the incident")
    List<SourceCodeSuggestion> sourceCodeSuggestions,
    @Schema(description = "Timestamp when the analysis was completed")
    LocalDateTime completedAt
) {
    public static AnalyzeFiringRecord create(GrafanaAlertingRequest request,
                                             String targetApplication,
                                             LokiQueryResult logResults,
                                             PrometheusQueryResult metricResults,
                                             String analyzeResults,
                                             List<SourceCodeSuggestion> sourceCodeSuggestions) {
        LocalDateTime occupiedAt = LocalDateTime.now();
        try {
            if (ObjectUtils.allNotNull(request, request.getAlerts(), request.getAlerts().getFirst(), request.getAlerts().getFirst().getStartsAt())) {
                occupiedAt = OffsetDateTime.parse(request.getAlerts().getFirst().getStartsAt()).toLocalDateTime();
            }
        } catch (Exception ignored) { }
        return new AnalyzeFiringRecord(occupiedAt, targetApplication, request, logResults, metricResults, analyzeResults, sourceCodeSuggestions, LocalDateTime.now());
    }
}
