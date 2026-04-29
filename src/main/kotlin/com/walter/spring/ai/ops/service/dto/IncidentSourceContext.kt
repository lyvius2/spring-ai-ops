package com.walter.spring.ai.ops.service.dto

data class IncidentSourceContext(
    val frames: List<StackTraceFrame>,
    val snippets: List<SourceSnippet>,
    val unresolvedFrames: List<StackTraceFrame>,
)
