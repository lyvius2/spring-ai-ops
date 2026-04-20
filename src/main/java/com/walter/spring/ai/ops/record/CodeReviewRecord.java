package com.walter.spring.ai.ops.record;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "AI code review record persisted after a Git push event")
public record CodeReviewRecord(
    @Schema(description = "Timestamp when the push event was received")
    LocalDateTime pushedAt,
    @Schema(description = "Application name")
    String application,
    @Schema(description = "HTML URL of the repository")
    String githubUrl,
    @Schema(description = "Latest commit message in the push")
    String commitMessage,
    @Schema(description = "List of files changed in the push")
    List<ChangedFile> changedFiles,
    @Schema(description = "LLM-generated code review result in Markdown")
    String reviewResult,
    @Schema(description = "Timestamp when the review was completed")
    LocalDateTime completedAt,
    @Schema(description = "Summary list of individual commits included in the push")
    List<CommitSummary> commitSummaries,
    @Schema(description = "Branch name that was pushed", example = "main")
    String branch
) {
    @JsonCreator
    public static CodeReviewRecord create(
        @JsonProperty("pushedAt") LocalDateTime pushedAt,
        @JsonProperty("application") String application,
        @JsonProperty("githubUrl") String githubUrl,
        @JsonProperty("commitMessage") String commitMessage,
        @JsonProperty("changedFiles") List<ChangedFile> changedFiles,
        @JsonProperty("reviewResult") String reviewResult,
        @JsonProperty("completedAt") LocalDateTime completedAt,
        @JsonProperty("commitSummaries") List<CommitSummary> commitSummaries,
        @JsonProperty("branch") String branch
    ) {
        return new CodeReviewRecord(pushedAt, application, githubUrl, commitMessage, changedFiles, reviewResult, completedAt, commitSummaries, branch);
    }
}
