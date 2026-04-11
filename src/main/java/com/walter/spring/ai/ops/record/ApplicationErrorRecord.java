package com.walter.spring.ai.ops.record;

import java.time.LocalDateTime;

public record ApplicationErrorRecord(
    LocalDateTime occupiedAt,
    String application,
    String alertingMessage,
    String log,
    String analysisResult,
    LocalDateTime completedAt
) { }
