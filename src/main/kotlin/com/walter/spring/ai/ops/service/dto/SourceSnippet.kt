package com.walter.spring.ai.ops.service.dto

data class SourceSnippet(
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val focusLine: Int?,
    val content: String,
) {
    fun createSourceSnippetPrompt(): String {
        return buildString {
            appendLine("File: $filePath")
            appendLine("Lines: $startLine-$endLine")
            if (focusLine != null) {
                appendLine("Focus line: $focusLine")
            }
            appendLine()
            appendLine(content)
        }
    }
}
