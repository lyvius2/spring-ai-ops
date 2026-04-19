package com.walter.spring.ai.ops.record;

import java.time.LocalDateTime;

public record CodeRiskRecord(
    LocalDateTime analyzedAt,
    String application,
    String githubUrl,
    String branch,
    String analyzedResult
) { }
