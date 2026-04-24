package com.walter.spring.ai.ops.record;

import com.walter.spring.ai.ops.connector.dto.LokiQueryResult;
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryResult;
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

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
    @Schema(description = "Timestamp when the analysis was completed")
    LocalDateTime completedAt
) { }
