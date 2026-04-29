package com.walter.spring.ai.ops.service.dto

data class IncidentSourceContext(
    val frames: List<StackTraceFrame>,
    val snippets: List<SourceSnippet>,
    val unresolvedFrames: List<StackTraceFrame>,
) {
    fun createSourceSectionPrompt(): String {
        if (snippets.isEmpty()) {
            return ""
        }

        return buildString {
            appendLine("## Related source snippets")
            appendLine("The following snippets were selected from JVM stack trace frames. Treat them as focused source context, not as the full repository.")
            appendLine()
            snippets.forEach { snippet ->
                appendLine(snippet.createSourceSnippetPrompt())
            }
        }
    }
}
