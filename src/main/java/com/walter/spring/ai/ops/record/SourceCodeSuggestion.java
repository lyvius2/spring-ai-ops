package com.walter.spring.ai.ops.record;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "AI-generated source code change suggestion related to an incident")
public record SourceCodeSuggestion(
    @Schema(description = "Repository-relative source file path")
    String filePath,
    @Schema(description = "Original source code related to the incident")
    String originalCode,
    @Schema(description = "Suggested replacement source code")
    String suggestionCode,
    @Schema(description = "Explanation of why this change is recommended")
    String description,
    @Schema(description = "Best-effort source line number related to the suggestion")
    Integer lineNumber
) { }
