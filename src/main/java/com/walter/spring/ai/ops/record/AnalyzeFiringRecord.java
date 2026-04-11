package com.walter.spring.ai.ops.record;

import com.walter.spring.ai.ops.connector.dto.LokiQueryResult;
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest;

import java.time.LocalDateTime;

public record AnalyzeFiringRecord(
    LocalDateTime occupiedAt,
    String application,
    GrafanaAlertingRequest alertingMessage,
    LokiQueryResult log,
    String analyzeResults,
    LocalDateTime completedAt
) { }
