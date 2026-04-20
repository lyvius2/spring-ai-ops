package com.walter.spring.ai.ops.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A single code risk issue identified by LLM analysis")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeRiskIssue(
    @Schema(description = "File path where the issue was found", example = "src/main/kotlin/Service.kt")
    String file,
    @Schema(description = "Line number or range of the issue", example = "42")
    String line,
    @Schema(description = "Severity level (CRITICAL / HIGH / MEDIUM / LOW)", example = "HIGH")
    String severity,
    @Schema(description = "Description of the identified risk")
    String description,
    @Schema(description = "Recommended fix or improvement")
    String recommendation,
    @Schema(description = "Relevant code snippet from the file", nullable = true)
    String codeSnippet
) {}
