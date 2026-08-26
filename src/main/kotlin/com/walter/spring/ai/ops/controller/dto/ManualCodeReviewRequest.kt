package com.walter.spring.ai.ops.controller.dto

data class ManualCodeReviewRequest(
    val branch: String,
    val commitShas: List<String>,
)
