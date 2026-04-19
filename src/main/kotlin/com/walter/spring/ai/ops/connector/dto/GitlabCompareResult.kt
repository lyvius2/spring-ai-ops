package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.walter.spring.ai.ops.record.ChangedFile
import com.walter.spring.ai.ops.record.CommitSummary

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitlabCompareResult(
    val commits: List<GitlabApiCommit> = emptyList(),
    val diffs: List<GitlabFile> = emptyList(),
    override val errorMessage: String = "",
) : GitCompareResult {
    override fun createCodeReviewPrompt(): String {
        return buildString {
            if (commits.isNotEmpty()) {
                appendLine("## Commits (${commits.size})")
                appendLine()
                commits.forEach { commit ->
                    val firstLine = commit.title.ifBlank { commit.message.lines().firstOrNull()?.trim() ?: "" }
                    appendLine("- ${commit.shortId}: $firstLine")
                }
                appendLine()
            }
            appendLine("## Code Diff")
            appendLine()
            diffs.forEach { diff ->
                val status = diffStatus(diff)
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

    override fun changedFiles(): List<ChangedFile> =
        diffs.map { ChangedFile(it.newPath, diffStatus(it), 0, 0, it.diff) }

    override fun commitSummaries(): List<CommitSummary> =
        commits.map { CommitSummary(it.id, it.message, it.webUrl, it.authoredDate) }

    private fun diffStatus(diff: GitlabFile): String = when {
        diff.newFile -> "added"
        diff.deletedFile -> "removed"
        diff.renamedFile -> "renamed"
        else -> "modified"
    }
}
