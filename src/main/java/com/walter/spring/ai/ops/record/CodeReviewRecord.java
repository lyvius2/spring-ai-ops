package com.walter.spring.ai.ops.record;

import java.time.LocalDateTime;

public record CodeReviewRecord(
    LocalDateTime pushedAt,
    String application,
    String pushedData,
    String commitMessage,
    String reviewResult,
    LocalDateTime completedAt
) { }
