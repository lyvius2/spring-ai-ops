package com.walter.spring.ai.ops.connector.dto

import com.walter.spring.ai.ops.record.ChangedFile
import com.walter.spring.ai.ops.record.CommitSummary

interface GitCompareResult {
    val errorMessage: String
    fun hasError(): Boolean = errorMessage.isNotBlank()
    fun createCodeReviewPrompt(): String
    fun changedFiles(): List<ChangedFile>
    fun commitSummaries(): List<CommitSummary>
}
