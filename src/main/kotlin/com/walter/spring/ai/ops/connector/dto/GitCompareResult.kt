package com.walter.spring.ai.ops.connector.dto

interface GitCompareResult {
    val errorMessage: String
    fun hasError(): Boolean = errorMessage.isNotBlank()
    fun createCodeReviewPrompt(): String
}

