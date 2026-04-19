package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.walter.spring.ai.ops.record.ChangedFile
import com.walter.spring.ai.ops.record.CommitSummary

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubCompareResult(
    val files: List<GithubFile> = emptyList(),
    val commits: List<GithubApiCommit> = emptyList(),
    override val errorMessage: String = "",
) : GitCompareResult {
    override fun createCodeReviewPrompt(): String {
        return buildString {
            if (commits.isNotEmpty()) {
                appendLine("## Commits (${commits.size})")
                appendLine()
                commits.forEach { commit ->
                    val shortSha = commit.sha.take(7)
                    val firstLine = commit.commit.message.lines().firstOrNull()?.trim() ?: ""
                    appendLine("- $shortSha: $firstLine")
                }
                appendLine()
            }
            appendLine("## Code Diff")
            appendLine()
            files.forEach { file ->
                appendLine("### ${file.filename} (${file.status})")
                appendLine("additions: ${file.additions}, deletions: ${file.deletions}")
                if (file.patch.isNotBlank()) {
                    appendLine("```diff")
                    appendLine(file.patch)
                    appendLine("```")
                }
                appendLine()
            }
        }
    }

    override fun changedFiles(): List<ChangedFile> =
        files.map { ChangedFile(it.filename, it.status, it.additions, it.deletions, it.patch) }

    override fun commitSummaries(): List<CommitSummary> =
        commits.map { CommitSummary(it.sha, it.commit.message, it.htmlUrl, it.commit.author.date) }
}
