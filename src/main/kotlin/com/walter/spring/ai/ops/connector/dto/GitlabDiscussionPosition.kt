package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.walter.spring.ai.ops.service.dto.HunkLine
import com.walter.spring.ai.ops.service.dto.ParsedFileDiff

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GitlabDiscussionPosition(
    @JsonProperty("base_sha") val baseSha: String,
    @JsonProperty("start_sha") val startSha: String,
    @JsonProperty("head_sha") val headSha: String,
    @JsonProperty("position_type") val positionType: String = "text",
    @JsonProperty("new_path") val newPath: String? = null,
    @JsonProperty("new_line") val newLine: Int? = null,
    @JsonProperty("old_path") val oldPath: String? = null,
    @JsonProperty("old_line") val oldLine: Int? = null,
) {
    companion object {
        @JvmStatic
        fun of(inquiry: GitDifferInquiry, parsedDiff: ParsedFileDiff, hunkLine: HunkLine): GitlabDiscussionPosition {
            return GitlabDiscussionPosition(
                baseSha = inquiry.base, startSha = inquiry.base, headSha = inquiry.head,
                newPath = parsedDiff.newPath, newLine = hunkLine.newLine,
                oldPath = parsedDiff.oldPath, oldLine = hunkLine.oldLine
            )
        }
    }
}