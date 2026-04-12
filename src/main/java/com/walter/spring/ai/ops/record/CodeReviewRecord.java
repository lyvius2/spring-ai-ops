package com.walter.spring.ai.ops.record;

import java.time.LocalDateTime;
import java.util.List;

public record CodeReviewRecord(
    LocalDateTime pushedAt,
    String application,
    String githubUrl,
    String commitMessage,
    List<ChangedFile> changedFiles,
    String reviewResult,
    LocalDateTime completedAt
) { }
