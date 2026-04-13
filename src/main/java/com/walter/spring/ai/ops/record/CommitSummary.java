package com.walter.spring.ai.ops.record;

public record CommitSummary(
    String id,
    String message,
    String url,
    String timestamp
) { }
