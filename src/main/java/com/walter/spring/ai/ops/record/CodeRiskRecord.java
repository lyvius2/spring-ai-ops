package com.walter.spring.ai.ops.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Code risk analysis record persisted after LLM static analysis")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeRiskRecord(
    @Schema(description = "Timestamp when the analysis was performed")
    LocalDateTime analyzedAt,
    @Schema(description = "Application name")
    String application,
    @Schema(description = "HTML URL of the repository")
    String githubUrl,
    @Schema(description = "Branch that was analysed", example = "main")
    String branch,
    @Schema(description = "Code risk analysis success status")
    Boolean isSuccess,
    @Schema(description = "LLM-generated overall analysis summary in Markdown")
    String analyzedResult,
    @Schema(description = "List of individual risk issues extracted from the analysis", nullable = true)
    List<CodeRiskIssue> issues
) {
    public static CodeRiskRecord failure(String application, String githubUrl, String branch, String resultMessage) {
        return new CodeRiskRecord(LocalDateTime.now(), application, githubUrl, branch, false, resultMessage, null);
    }
}
