package com.walter.spring.ai.ops.record;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

public record CodeReviewRecord(
    LocalDateTime pushedAt,
    String application,
    String githubUrl,
    String commitMessage,
    List<ChangedFile> changedFiles,
    String reviewResult,
    LocalDateTime completedAt,
    List<CommitSummary> commitSummaries,
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
