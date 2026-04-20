package com.walter.spring.ai.ops.record;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeRiskRecord(
    LocalDateTime analyzedAt,
    String application,
    String githubUrl,
    String branch,
    String analyzedResult,
    List<CodeRiskIssue> issues
) {}
