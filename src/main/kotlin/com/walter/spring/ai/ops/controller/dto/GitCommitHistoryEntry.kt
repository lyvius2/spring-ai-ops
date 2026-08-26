package com.walter.spring.ai.ops.controller.dto

import com.walter.spring.ai.ops.record.ChangedFile

data class GitCommitHistoryEntry(
    val sha: String,
    val title: String,
    val author: String,
    val committedAt: String,
    val url: String,
    val changedFiles: List<ChangedFile>,
)
