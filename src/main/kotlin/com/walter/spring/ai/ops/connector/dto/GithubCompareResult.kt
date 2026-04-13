package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubCompareResult(
    val files: List<GithubFile> = emptyList(),
    val commits: List<GithubApiCommit> = emptyList(),
    val errorMessage: String = "",
) {
    fun createCodeReviewPrompt(): String {
        return buildString {
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
}
