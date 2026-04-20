package com.walter.spring.ai.ops.record;

public record CodeRiskIssue(
    String file,
    String line,
    String severity,
    String description,
    String recommendation
) {}