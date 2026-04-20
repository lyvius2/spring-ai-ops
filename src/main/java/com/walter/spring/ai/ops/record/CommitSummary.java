package com.walter.spring.ai.ops.record;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Brief summary of an individual commit within a push event")
public record CommitSummary(
    @Schema(description = "Commit SHA", example = "abc1234")
    String id,
    @Schema(description = "Commit message")
    String message,
    @Schema(description = "URL to the commit on the remote provider")
    String url,
    @Schema(description = "Commit timestamp in ISO-8601 format", example = "2024-01-01T00:00:00Z")
    String timestamp
) { }
