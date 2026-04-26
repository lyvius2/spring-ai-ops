package com.walter.spring.ai.ops.sonar.service.dto

data class SonarIssue(
    val ruleKey: String,
    val severity: String,
    val component: String,
    val line: Int?,
    val message: String
)
