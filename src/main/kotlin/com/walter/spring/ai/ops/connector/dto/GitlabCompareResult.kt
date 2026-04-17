package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitlabCompareResult(
    val commits: List<GitlabApiCommit> = emptyList(),
    val diffs: List<GitlabFile> = emptyList(),
    override val errorMessage: String = "",
) : GitCompareResult {
    override fun createCodeReviewPrompt(): String {
        return buildString {
            appendLine("## Code Diff")
            appendLine()
            diffs.forEach { diff ->
                val status = when {
                    diff.newFile -> "added"
                    diff.deletedFile -> "removed"
                    diff.renamedFile -> "renamed"
                    else -> "modified"
                }
                val path = if (diff.renamedFile) "${diff.oldPath} → ${diff.newPath}" else diff.newPath
                appendLine("### $path ($status)")
                if (diff.diff.isNotBlank()) {
                    appendLine("```diff")
                    appendLine(diff.diff)
                    appendLine("```")
                }
                appendLine()
            }
        }
    }
}
