package com.walter.spring.ai.ops.service.dto

data class SourceSnippet(
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val focusLine: Int?,
    val content: String,
)
