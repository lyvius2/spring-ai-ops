package com.walter.spring.ai.ops.sonar.service.dto

data class SonarScanResult(
    val projectKey: String,
    val success: Boolean
)
