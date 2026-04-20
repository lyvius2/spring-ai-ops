package com.walter.spring.ai.ops.record;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A single file changed in a Git commit")
public record ChangedFile(
    @Schema(description = "File path relative to repository root", example = "src/main/kotlin/App.kt")
    String filename,
    @Schema(description = "Change status (added / modified / removed)", example = "modified")
    String status,
    @Schema(description = "Number of lines added", example = "10")
    int additions,
    @Schema(description = "Number of lines deleted", example = "3")
    int deletions,
    @Schema(description = "Unified diff patch of the file change")
    String patch
) { }
