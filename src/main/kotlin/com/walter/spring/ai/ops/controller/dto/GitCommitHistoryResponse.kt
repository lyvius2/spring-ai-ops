package com.walter.spring.ai.ops.controller.dto

data class GitCommitHistoryResponse(
    val branch: String,
    val commits: List<GitCommitHistoryEntry>,
)
